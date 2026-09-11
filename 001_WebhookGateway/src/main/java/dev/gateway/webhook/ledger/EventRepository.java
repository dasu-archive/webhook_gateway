package dev.gateway.webhook.ledger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EventRepository {

    private static final String COLUMNS = """
            id, endpoint_id, idempotency_key, idempotency_source, raw_body, raw_headers,
            content_type, body_size, status, attempt_count, last_backoff_ms,
            next_attempt_at, received_at, updated_at, replay_count, last_replay_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public EventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 수신 경로의 유일한 쓰기. 설계 P1 — 검증과 저장만 한다.
     * 중복은 조회 후 삽입이 아니라 유니크 인덱스로 막는다(설계 7.4).
     * 호출자는 DuplicateKeyException 을 200 으로 번역한다.
     */
    public long insert(long endpointId, String idempotencyKey, IdempotencySource source,
                       byte[] rawBody, Map<String, String> rawHeaders, String contentType) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO event
                            (endpoint_id, idempotency_key, idempotency_source, raw_body, raw_headers,
                             content_type, body_size, status, attempt_count, next_attempt_at,
                             received_at, updated_at)
                        VALUES (:endpointId, :key, :source, :body, :headers,
                                :contentType, :size, 'PENDING', 0, NOW(3),
                                NOW(3), NOW(3))
                        """)
                .param("endpointId", endpointId)
                .param("key", idempotencyKey)
                .param("source", source.name())
                .param("body", rawBody)
                .param("headers", writeJson(rawHeaders))
                .param("contentType", contentType)
                .param("size", rawBody.length)
                .update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("event id 를 받지 못했다");
        }
        return id.longValue();
    }

    /**
     * 전달부 폴링. 설계 10.1.
     * SELECT ... FOR UPDATE SKIP LOCKED 로 여러 워커가 서로 다른 행을 집는다.
     * 페이로드를 읽지 않고 id 만 집어 트랜잭션을 짧게 유지한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> claimBatch(int limit) {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM event
                         WHERE status = 'PENDING'
                           AND next_attempt_at <= NOW(3)
                         ORDER BY next_attempt_at
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query(Long.class)
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("""
                        UPDATE event
                           SET status = 'DELIVERING',
                               attempt_count = attempt_count + 1,
                               updated_at = NOW(3)
                         WHERE id IN (:ids)
                        """)
                .param("ids", ids)
                .update();
        return ids;
    }

    public Optional<Event> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM event WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public void markDelivered(long id) {
        jdbc.sql("""
                        UPDATE event
                           SET status = 'DELIVERED', next_attempt_at = NULL, updated_at = NOW(3)
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public void scheduleRetry(long id, Instant nextAttemptAt, long backoffMs) {
        jdbc.sql("""
                        UPDATE event
                           SET status = 'PENDING',
                               next_attempt_at = :next,
                               last_backoff_ms = :backoff,
                               updated_at = NOW(3)
                         WHERE id = :id
                        """)
                .param("next", Timestamp.from(nextAttemptAt))
                .param("backoff", (int) Math.min(backoffMs, Integer.MAX_VALUE))
                .param("id", id)
                .update();
    }

    public void markDead(long id) {
        jdbc.sql("""
                        UPDATE event
                           SET status = 'DEAD', next_attempt_at = NULL, updated_at = NOW(3)
                         WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /**
     * 좀비 회수. 설계 7.2.1.
     * DELIVERING 인 채로 프로세스가 죽으면 그 행은 폴링 쿼리(PENDING 만 본다)에 영영 안 잡힌다.
     * 회수하면 소비자가 이미 처리한 것을 다시 보낼 수 있다 — at-least-once 의 대가다.
     */
    public int reclaimZombies(Duration olderThan) {
        return jdbc.sql("""
                        UPDATE event
                           SET status = 'PENDING', next_attempt_at = NOW(3), updated_at = NOW(3)
                         WHERE status = 'DELIVERING'
                           AND updated_at < DATE_SUB(NOW(3), INTERVAL :seconds SECOND)
                        """)
                .param("seconds", olderThan.toSeconds())
                .update();
    }

    /**
     * @param replayId     재생 뒤의 시도면 그 재생 id. 원래 시도는 null
     * @param outcome      판정 — 재시도할지
     * @param failureClass 원인 — 어느 단계에서 끝났나. outcome 과 독립이다
     * @param backoffMs    이 시도 뒤에 기다리기로 한 시간. RETRY 가 아니면 null
     */
    public void recordAttempt(long eventId, int attemptNo, Long replayId, Instant startedAt, long durationMs,
                              Integer responseStatus, DeliveryOutcome outcome, FailureClass failureClass,
                              Long backoffMs, String errorMessage) {
        jdbc.sql("""
                        INSERT INTO delivery_attempt
                            (event_id, attempt_no, replay_id, started_at, duration_ms, response_status,
                             outcome, failure_class, backoff_ms, error_message)
                        VALUES (:eventId, :attemptNo, :replayId, :startedAt, :durationMs, :responseStatus,
                                :outcome, :failureClass, :backoffMs, :error)
                        """)
                .param("eventId", eventId)
                .param("attemptNo", attemptNo)
                .param("replayId", replayId)
                .param("startedAt", Timestamp.from(startedAt))
                .param("durationMs", (int) Math.min(durationMs, Integer.MAX_VALUE))
                .param("responseStatus", responseStatus)
                .param("outcome", outcome.name())
                .param("failureClass", failureClass.name())
                .param("backoffMs", backoffMs == null ? null : (int) Math.min(backoffMs, Integer.MAX_VALUE))
                .param("error", truncate(errorMessage, 512))
                .update();
    }

    public List<DeliveryAttempt> findAttempts(long eventId) {
        return jdbc.sql("""
                        SELECT id, event_id, attempt_no, replay_id, started_at, duration_ms,
                               response_status, outcome, failure_class, backoff_ms, error_message
                          FROM delivery_attempt
                         WHERE event_id = :eventId
                         ORDER BY id
                        """)
                // attempt_no 는 재생하면 1 부터 다시 시작해 겹친다. 기록 순서(id)가 곧 시간 순서다.
                .param("eventId", eventId)
                .query((rs, n) -> new DeliveryAttempt(
                        rs.getLong("id"),
                        rs.getLong("event_id"),
                        rs.getInt("attempt_no"),
                        (Long) rs.getObject("replay_id"),
                        EndpointRepository.instant(rs, "started_at"),
                        (Integer) rs.getObject("duration_ms"),
                        (Integer) rs.getObject("response_status"),
                        DeliveryOutcome.valueOf(rs.getString("outcome")),
                        failureClass(rs.getString("failure_class")),
                        (Integer) rs.getObject("backoff_ms"),
                        rs.getString("error_message")))
                .list();
    }

    /** V2 이전 행은 NULL 이다. */
    private static FailureClass failureClass(String value) {
        return value == null ? null : FailureClass.valueOf(value);
    }

    public List<Event> search(Long endpointId, EventStatus status, int limit, long afterId) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM event WHERE id > :afterId");
        if (endpointId != null) {
            sql.append(" AND endpoint_id = :endpointId");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY id LIMIT :limit");

        var spec = jdbc.sql(sql.toString())
                .param("afterId", afterId)
                .param("limit", limit);
        if (endpointId != null) {
            spec = spec.param("endpointId", endpointId);
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::map).list();
    }

    public Map<String, Long> countByStatus() {
        var counts = new LinkedHashMap<String, Long>();
        for (EventStatus s : EventStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql("SELECT status, COUNT(*) AS c FROM event GROUP BY status")
                .query((rs, n) -> counts.put(rs.getString("status"), rs.getLong("c")))
                .list();
        return counts;
    }

    private Event map(ResultSet rs, int rowNum) throws SQLException {
        return new Event(
                rs.getLong("id"),
                rs.getLong("endpoint_id"),
                rs.getString("idempotency_key"),
                IdempotencySource.valueOf(rs.getString("idempotency_source")),
                rs.getBytes("raw_body"),
                readJson(rs.getString("raw_headers")),
                rs.getString("content_type"),
                rs.getInt("body_size"),
                EventStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                (Integer) rs.getObject("last_backoff_ms"),
                EndpointRepository.instant(rs, "next_attempt_at"),
                EndpointRepository.instant(rs, "received_at"),
                EndpointRepository.instant(rs, "updated_at"),
                rs.getInt("replay_count"),
                (Long) rs.getObject("last_replay_id")
        );
    }

    private String writeJson(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalStateException("헤더 직렬화 실패", e);
        }
    }

    private Map<String, String> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("헤더 역직렬화 실패", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
