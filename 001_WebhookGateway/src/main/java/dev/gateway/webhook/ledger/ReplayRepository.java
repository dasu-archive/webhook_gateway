package dev.gateway.webhook.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 재생. README 3.6.
 *
 * <p>범위는 "한 엔드포인트의 DEAD 중 수신 시각 [from, to)" 하나다. 드라이런이 세는 조건과
 * 실행이 옮기는 조건이 어긋나면 드라이런이 거짓말을 하게 되므로, 조건 문자열을 여기 한 곳에 둔다.
 */
@Repository
public class ReplayRepository {

    /** 가드 1번. 4xx(408·429 제외)로 죽은 건은 다시 보내도 같은 결과다. 응답 코드로 보므로 V2 이전 행에도 걸린다. */
    private static final String DIED_ON_4XX = """
            EXISTS (SELECT 1 FROM delivery_attempt da
                     WHERE da.event_id = e.id
                       AND da.outcome = 'DEAD'
                       AND da.response_status BETWEEN 400 AND 499
                       AND da.response_status NOT IN (408, 429))""";

    /** 가드 2번이 먼저다. 재생됐다 다시 죽은 건은 원인과 상관없이 사람이 봐야 한다. */
    private static final String VERDICT = "CASE WHEN e.replay_count > 0 THEN 'ALREADY_REPLAYED'"
            + " WHEN " + DIED_ON_4XX + " THEN 'PERMANENT_4XX'"
            + " ELSE 'ELIGIBLE' END";

    private static final String ELIGIBLE = "e.replay_count = 0 AND NOT " + DIED_ON_4XX;

    private static final String COLUMNS = """
            r.id, r.endpoint_id, ep.slug, r.received_from, r.received_to,
            r.requested_by, r.reason, r.event_count, r.created_at""";

    private final JdbcClient jdbc;

    public ReplayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 범위 안의 DEAD 를 판정별로 센다. 드라이런용이라 아무것도 바꾸지 않는다. */
    public Map<ReplayVerdict, Long> classify(long endpointId, Instant from, Instant to) {
        var counts = new EnumMap<ReplayVerdict, Long>(ReplayVerdict.class);
        for (ReplayVerdict v : ReplayVerdict.values()) {
            counts.put(v, 0L);
        }
        jdbc.sql("SELECT " + VERDICT + " AS verdict, COUNT(*) AS c FROM event e WHERE "
                        + scope(from, to) + " GROUP BY verdict")
                .params(scopeParams(endpointId, from, to))
                .query((rs, n) -> counts.put(ReplayVerdict.valueOf(rs.getString("verdict")), rs.getLong("c")))
                .list();
        return counts;
    }

    /** 사람이 눈으로 확인할 표본. 전부 돌려주지 않는다. */
    public List<Long> sampleEligible(long endpointId, Instant from, Instant to, int limit) {
        var params = scopeParams(endpointId, from, to);
        params.put("limit", limit);
        return jdbc.sql("SELECT e.id FROM event e WHERE " + scope(from, to) + " AND " + ELIGIBLE
                        + " ORDER BY e.id LIMIT :limit")
                .params(params)
                .query(Long.class)
                .list();
    }

    public long insert(long endpointId, Instant from, Instant to, String requestedBy, String reason, int eventCount) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO replay
                            (endpoint_id, received_from, received_to, requested_by, reason, event_count, created_at)
                        VALUES (:endpointId, :from, :to, :requestedBy, :reason, :eventCount, NOW(3))
                        """)
                .param("endpointId", endpointId)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("requestedBy", requestedBy)
                .param("reason", reason)
                .param("eventCount", eventCount)
                .update(keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("replay id 를 받지 못했다");
        }
        return id.longValue();
    }

    /**
     * DEAD → PENDING. <b>제자리 전이</b>라 event.id 가 그대로이고, 소비자가 받는 X-Gateway-Event-Id 도 그대로다.
     * attempt_count 를 0 으로 되돌려 재시도 예산과 백오프를 처음부터 다시 쓴다.
     *
     * @return 옮긴 건수. 호출자는 드라이런에서 본 숫자와 비교해야 한다
     */
    public int markForReplay(long replayId, long endpointId, Instant from, Instant to) {
        var params = scopeParams(endpointId, from, to);
        params.put("replayId", replayId);
        return jdbc.sql("""
                        UPDATE event e
                           SET e.status = 'PENDING',
                               e.attempt_count = 0,
                               e.last_backoff_ms = NULL,
                               e.next_attempt_at = NOW(3),
                               e.updated_at = NOW(3),
                               e.replay_count = e.replay_count + 1,
                               e.last_replay_id = :replayId
                         WHERE\s""" + scope(from, to) + " AND " + ELIGIBLE)
                .params(params)
                .update();
    }

    public Optional<Replay> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM replay r JOIN endpoint ep ON ep.id = r.endpoint_id WHERE r.id = :id")
                .param("id", id)
                .query(ReplayRepository::map)
                .optional();
    }

    public List<Replay> findRecent(Long endpointId, int limit) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM replay r JOIN endpoint ep ON ep.id = r.endpoint_id");
        if (endpointId != null) {
            sql.append(" WHERE r.endpoint_id = :endpointId");
        }
        sql.append(" ORDER BY r.id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (endpointId != null) {
            spec = spec.param("endpointId", endpointId);
        }
        return spec.query(ReplayRepository::map).list();
    }

    /** 이 재생으로 옮긴 이벤트가 지금 어떤 상태인가. 재생이 실제로 소진됐는지 보는 창구다. */
    public Map<String, Long> countEventsByStatus(long replayId) {
        var counts = new LinkedHashMap<String, Long>();
        for (EventStatus s : EventStatus.values()) {
            counts.put(s.name(), 0L);
        }
        jdbc.sql("SELECT status, COUNT(*) AS c FROM event WHERE last_replay_id = :id GROUP BY status")
                .param("id", replayId)
                .query((rs, n) -> counts.put(rs.getString("status"), rs.getLong("c")))
                .list();
        return counts;
    }

    private static String scope(Instant from, Instant to) {
        var sql = new StringBuilder("e.endpoint_id = :endpointId AND e.status = 'DEAD'");
        if (from != null) {
            sql.append(" AND e.received_at >= :from");
        }
        if (to != null) {
            sql.append(" AND e.received_at < :to");
        }
        return sql.toString();
    }

    private static Map<String, Object> scopeParams(long endpointId, Instant from, Instant to) {
        var params = new HashMap<String, Object>();
        params.put("endpointId", endpointId);
        if (from != null) {
            params.put("from", Timestamp.from(from));
        }
        if (to != null) {
            params.put("to", Timestamp.from(to));
        }
        return params;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Replay map(ResultSet rs, int rowNum) throws SQLException {
        return new Replay(
                rs.getLong("id"),
                rs.getLong("endpoint_id"),
                rs.getString("slug"),
                EndpointRepository.instant(rs, "received_from"),
                EndpointRepository.instant(rs, "received_to"),
                rs.getString("requested_by"),
                rs.getString("reason"),
                rs.getInt("event_count"),
                EndpointRepository.instant(rs, "created_at"));
    }
}
