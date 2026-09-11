package dev.gateway.webhook;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gateway.webhook.dispatch.EventDispatcher;
import dev.gateway.webhook.dispatch.FailureClassifier;
import dev.gateway.webhook.ledger.DeliveryAttempt;
import dev.gateway.webhook.ledger.DeliveryOutcome;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.EventRepository;
import dev.gateway.webhook.ledger.EventStatus;
import dev.gateway.webhook.ledger.IdempotencySource;
import dev.gateway.webhook.ledger.SignatureMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 재생. README 3.6, 체크리스트 B-1. */
class ReplayIntegrationTest extends IntegrationTestBase {

    private static final String BODY = "{\"orderId\": 123}";
    private static final byte[] SECRET = "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8);

    private static HttpServer consumer;
    private static int consumerPort;
    private static final List<Map<String, String>> delivered = new CopyOnWriteArrayList<>();

    @Autowired
    WebApplicationContext context;
    @Autowired
    EndpointRepository endpoints;
    @Autowired
    EventRepository events;
    @Autowired
    EventDispatcher dispatcher;

    @BeforeAll
    static void startConsumer() throws IOException {
        consumer = HttpServer.create(new InetSocketAddress(0), 0);
        consumer.createContext("/consume", ReplayIntegrationTest::handle);
        consumer.start();
        consumerPort = consumer.getAddress().getPort();
    }

    @AfterAll
    static void stopConsumer() {
        consumer.stop(0);
    }

    /** 스프링 MVC 의 POST 전용 핸들러처럼 HEAD 에는 405 로 답한다. 헬스체크는 응답이 오는지만 본다. */
    private static void handle(HttpExchange exchange) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        exchange.getRequestBody().readAllBytes();
        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        delivered.add(headers);
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    @BeforeEach
    void resetConsumer() {
        delivered.clear();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long liveEndpoint(String slug) {
        return endpoints.insert(slug, "github", SECRET, "http://localhost:" + consumerPort + "/consume",
                SignatureMode.PASSTHROUGH, 8, 7);
    }

    /** 마지막 시도가 lastStatus 로 끝나 DEAD 가 된 이벤트. */
    private long deadEvent(long endpointId, String key, int lastStatus) {
        long id = events.insert(endpointId, key, IdempotencySource.PROVIDER_ID,
                BODY.getBytes(StandardCharsets.UTF_8), Map.of("X-GitHub-Event", "push"), "application/json");
        jdbc.sql("UPDATE event SET status = 'DEAD', attempt_count = 1, next_attempt_at = NULL WHERE id = :id")
                .param("id", id).update();
        events.recordAttempt(id, 1, null, Instant.now(), 5, lastStatus, DeliveryOutcome.DEAD,
                FailureClassifier.ofStatus(lastStatus), null, "소비자 응답 " + lastStatus);
        return id;
    }

    private void confirmIdempotency(String slug) throws Exception {
        mvc().perform(post("/admin/endpoints/" + slug + "/idempotency").param("confirmed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyConfirmed").value(true));
    }

    private ResultActions dryRun(String json) throws Exception {
        return mvc().perform(post("/admin/replays/dry-run").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions execute(String json) throws Exception {
        return mvc().perform(post("/admin/replays").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String executeBody(String slug, int expectedCount) {
        return """
                {"endpoint": "%s", "expectedCount": %d, "requestedBy": "dasu", "reason": "소비자 복구 후 재전달"}
                """.formatted(slug, expectedCount);
    }

    private void runOneCycle() {
        for (long id : events.claimBatch(50)) {
            dispatcher.dispatch(id);
        }
    }

    private EventStatus statusOf(long eventId) {
        return events.findById(eventId).orElseThrow().status();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    @DisplayName("드라이런은 DEAD 중 재생할 것만 세고 제외 사유를 나누며, 아무것도 바꾸지 않는다")
    void dryRunClassifiesWithoutChanging() throws Exception {
        long a = liveEndpoint("svc-a");
        long b = liveEndpoint("svc-b");
        deadEvent(a, "e1", 503);
        deadEvent(a, "e2", 500);
        deadEvent(a, "e3", 429);                 // 4xx 지만 재시도 대상이었다. 재생도 된다
        deadEvent(a, "e4", 400);                 // 가드 1번 — 페이로드 문제라 다시 보내도 같다
        long replayed = deadEvent(a, "e5", 503); // 가드 2번
        jdbc.sql("UPDATE event SET replay_count = 1 WHERE id = :id").param("id", replayed).update();
        events.insert(a, "e6", IdempotencySource.PROVIDER_ID, BODY.getBytes(StandardCharsets.UTF_8),
                Map.of(), "application/json");   // PENDING — DEAD 가 아니라 범위 밖
        deadEvent(b, "e7", 503);                 // 다른 엔드포인트

        dryRun("{\"endpoint\": \"svc-a\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEAD"))
                .andExpect(jsonPath("$.matched").value(5))
                .andExpect(jsonPath("$.eligible").value(3))
                .andExpect(jsonPath("$.skipped.PERMANENT_4XX").value(1))
                .andExpect(jsonPath("$.skipped.ALREADY_REPLAYED").value(1))
                .andExpect(jsonPath("$.sampleEventIds", hasSize(3)))
                .andExpect(jsonPath("$.guards.consumerHealthy").value(true))
                .andExpect(jsonPath("$.guards.consumerProbe").value("HTTP 405"))
                .andExpect(jsonPath("$.guards.idempotencyConfirmed").value(false))
                // 멱등 선언이 없어 실행할 수 없다는 걸 드라이런에서 미리 보여준다
                .andExpect(jsonPath("$.executable").value(false));

        assertThat(count("SELECT COUNT(*) FROM event WHERE status = 'DEAD'")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM replay")).isZero();
    }

    @Test
    @DisplayName("수신 시각 [receivedFrom, receivedTo) 로 좁히고, 거꾸로 된 범위는 400")
    void dryRunRespectsReceivedRange() throws Exception {
        long a = liveEndpoint("svc-a");
        long old = deadEvent(a, "old", 503);
        long recent = deadEvent(a, "recent", 503);
        jdbc.sql("UPDATE event SET received_at = DATE_SUB(NOW(3), INTERVAL 3 DAY) WHERE id = :id")
                .param("id", old).update();
        String dayAgo = Instant.now().minus(Duration.ofDays(1)).toString();
        String now = Instant.now().toString();

        dryRun("{\"endpoint\": \"svc-a\", \"receivedFrom\": \"" + dayAgo + "\"}")
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sampleEventIds[0]").value(recent));
        dryRun("{\"endpoint\": \"svc-a\", \"receivedTo\": \"" + dayAgo + "\"}")
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.sampleEventIds[0]").value(old));
        dryRun("{\"endpoint\": \"svc-a\", \"receivedFrom\": \"" + now + "\", \"receivedTo\": \"" + dayAgo + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("멱등 처리 선언이 없으면 409 IDEMPOTENCY_NOT_CONFIRMED 이고 원장은 그대로다 — 가드 4번")
    void rejectsWithoutIdempotencyDeclaration() throws Exception {
        long a = liveEndpoint("svc-a");
        long id = deadEvent(a, "e1", 503);

        // 요청자와 사유는 필수다. 이력에 "누가 왜"가 빠지면 이력이 아니다
        execute("{\"endpoint\": \"svc-a\", \"expectedCount\": 1}").andExpect(status().isBadRequest());

        execute(executeBody("svc-a", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_NOT_CONFIRMED"));
        assertThat(statusOf(id)).isEqualTo(EventStatus.DEAD);
        assertThat(count("SELECT COUNT(*) FROM replay")).isZero();
    }

    @Test
    @DisplayName("소비자가 응답하지 않으면 409 CONSUMER_UNHEALTHY — 가드 3번")
    void rejectsWhenConsumerIsDown() throws Exception {
        long down = endpoints.insert("svc-down", "github", SECRET, "http://localhost:1/consume",
                SignatureMode.PASSTHROUGH, 8, 7);
        confirmIdempotency("svc-down");
        long id = deadEvent(down, "e1", 503);

        dryRun("{\"endpoint\": \"svc-down\"}")
                .andExpect(jsonPath("$.guards.consumerHealthy").value(false))
                .andExpect(jsonPath("$.guards.consumerProbe").value("CONN_REFUSED"))
                .andExpect(jsonPath("$.executable").value(false));
        execute(executeBody("svc-down", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CONSUMER_UNHEALTHY"));
        assertThat(statusOf(id)).isEqualTo(EventStatus.DEAD);
    }

    @Test
    @DisplayName("드라이런에서 본 건수와 실행 시점 건수가 다르면 409 COUNT_MISMATCH 이고 이력도 전이도 없던 일이 된다")
    void rejectsOnCountMismatch() throws Exception {
        long a = liveEndpoint("svc-a");
        confirmIdempotency("svc-a");
        long e1 = deadEvent(a, "e1", 503);
        long e2 = deadEvent(a, "e2", 503);

        // 드라이런에서 1건을 봤는데 그사이 하나가 더 죽은 상황
        execute(executeBody("svc-a", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("COUNT_MISMATCH"));

        assertThat(statusOf(e1)).isEqualTo(EventStatus.DEAD);
        assertThat(statusOf(e2)).isEqualTo(EventStatus.DEAD);
        assertThat(count("SELECT COUNT(*) FROM replay")).isZero();
    }

    @Test
    @DisplayName("실행하면 DEAD 가 제자리에서 PENDING 이 되고, 워커가 같은 이벤트 ID 에 재생 표시를 붙여 다시 보낸다")
    void replaysInPlace() throws Exception {
        long a = liveEndpoint("svc-a");
        confirmIdempotency("svc-a");
        long id = deadEvent(a, "e1", 503);
        long bad = deadEvent(a, "e2", 400);

        String body = execute(executeBody("svc-a", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpoint").value("svc-a"))
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.requestedBy").value("dasu"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long replayId = ((Number) JsonPath.read(body, "$.id")).longValue();

        var event = events.findById(id).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.PENDING);
        assertThat(event.attemptCount()).isZero();
        assertThat(event.replayCount()).isEqualTo(1);
        assertThat(event.lastReplayId()).isEqualTo(replayId);
        assertThat(statusOf(bad)).isEqualTo(EventStatus.DEAD);

        runOneCycle();

        assertThat(statusOf(id)).isEqualTo(EventStatus.DELIVERED);
        assertThat(delivered).hasSize(1);
        var headers = delivered.get(0);
        // 새 행이 아니라 같은 행이다. 소비자의 멱등 키가 그대로라 이미 처리한 건이면 소비자가 걸러낸다
        assertThat(headers).containsEntry("x-gateway-event-id", String.valueOf(id));
        assertThat(headers).containsEntry("x-gateway-replay", "true");
        assertThat(headers).containsEntry("x-gateway-attempt", "1");

        // attempt_no 는 1 부터 다시 시작하고, 원래 시도와 재생 뒤 시도는 replay_id 로 갈린다
        var attempts = events.findAttempts(id);
        assertThat(attempts).extracting(DeliveryAttempt::attemptNo).containsExactly(1, 1);
        assertThat(attempts).extracting(DeliveryAttempt::replayId).containsExactly(null, replayId);
        assertThat(attempts).extracting(DeliveryAttempt::outcome)
                .containsExactly(DeliveryOutcome.DEAD, DeliveryOutcome.DELIVERED);

        mvc().perform(get("/admin/replays/" + replayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay.reason").value("소비자 복구 후 재전달"))
                .andExpect(jsonPath("$.eventsByStatus.DELIVERED").value(1));
        mvc().perform(get("/admin/replays").param("endpoint", "svc-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("한 번 재생된 건은 다시 죽어도 재생 대상이 아니다 — 가드 2번")
    void replayedEventIsNotReplayedTwice() throws Exception {
        long a = liveEndpoint("svc-a");
        confirmIdempotency("svc-a");
        long id = deadEvent(a, "e1", 503);
        execute(executeBody("svc-a", 1)).andExpect(status().isCreated());

        // 재생 뒤에도 다시 죽었다고 치자
        jdbc.sql("UPDATE event SET status = 'DEAD' WHERE id = :id").param("id", id).update();

        dryRun("{\"endpoint\": \"svc-a\"}")
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.eligible").value(0))
                .andExpect(jsonPath("$.skipped.ALREADY_REPLAYED").value(1))
                .andExpect(jsonPath("$.executable").value(false));
    }
}
