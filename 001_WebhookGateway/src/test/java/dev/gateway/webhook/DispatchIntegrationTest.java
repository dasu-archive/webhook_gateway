package dev.gateway.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gateway.webhook.dispatch.EventDispatcher;
import dev.gateway.webhook.ledger.DeliveryOutcome;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.EventRepository;
import dev.gateway.webhook.ledger.EventStatus;
import dev.gateway.webhook.ledger.FailureClass;
import dev.gateway.webhook.ledger.IdempotencySource;
import dev.gateway.webhook.ledger.SignatureMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** 전달부. 설계 8절과 7.2.1. */
class DispatchIntegrationTest extends IntegrationTestBase {

    private static final String BODY = "{\"orderId\": 123, \"status\": \"DONE\"}";

    private static HttpServer consumer;
    private static int consumerPort;

    /** 요청 번호(1부터)를 받아 응답 상태를 정하는 시나리오. 목 소비자와 같은 발상이다. */
    private static volatile IntUnaryOperator scenario = n -> 200;
    private static final AtomicInteger requestCount = new AtomicInteger();
    private static final List<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();
    private static final List<byte[]> receivedBodies = new CopyOnWriteArrayList<>();

    @Autowired
    EndpointRepository endpoints;
    @Autowired
    EventRepository events;
    @Autowired
    EventDispatcher dispatcher;

    @BeforeAll
    static void startConsumer() throws IOException {
        consumer = HttpServer.create(new InetSocketAddress(0), 0);
        consumer.createContext("/consume", DispatchIntegrationTest::handle);
        consumer.setExecutor(Executors.newFixedThreadPool(8));
        consumer.start();
        consumerPort = consumer.getAddress().getPort();
    }

    @AfterAll
    static void stopConsumer() {
        consumer.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        receivedBodies.add(body);

        var headers = new HashMap<String, String>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        });
        receivedHeaders.add(headers);

        int status = scenario.applyAsInt(requestCount.incrementAndGet());
        exchange.sendResponseHeaders(status, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(new byte[0]);
        }
    }

    @BeforeEach
    void resetConsumer() {
        scenario = n -> 200;
        requestCount.set(0);
        receivedHeaders.clear();
        receivedBodies.clear();
    }

    private long registerEndpoint(int maxAttempts) {
        return endpoints.insert("gh-push", "github", "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8),
                "http://localhost:" + consumerPort + "/consume", SignatureMode.PASSTHROUGH, maxAttempts, 7);
    }

    private long enqueue(long endpointId, String key) {
        return events.insert(endpointId, key, IdempotencySource.PROVIDER_ID,
                BODY.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Hub-Signature-256", "sha256=deadbeef", "X-GitHub-Event", "push"),
                "application/json");
    }

    /** 폴링 한 사이클. 워커가 하는 일과 같되 스케줄러 없이 결정적으로 돈다. */
    private List<Long> runOneCycle() {
        var claimed = events.claimBatch(50);
        var dispatched = new ArrayList<Long>();
        for (long id : claimed) {
            dispatcher.dispatch(id);
            dispatched.add(id);
        }
        return dispatched;
    }

    @Test
    @DisplayName("2xx 를 받으면 DELIVERED 이고 원본 바이트와 게이트웨이 헤더가 소비자에 도착한다")
    void deliversSuccessfully() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");

        runOneCycle();

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.DELIVERED);
        assertThat(event.attemptCount()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isNull();

        assertThat(receivedBodies).hasSize(1);
        assertThat(new String(receivedBodies.get(0), StandardCharsets.UTF_8)).isEqualTo(BODY);

        var headers = receivedHeaders.get(0);
        // 소비자는 이 헤더로 중복을 다시 걸러야 한다(설계 7.6, 15).
        assertThat(headers).containsEntry("x-gateway-event-id", String.valueOf(eventId));
        assertThat(headers).containsEntry("x-gateway-endpoint", "gh-push");
        assertThat(headers).containsEntry("x-gateway-attempt", "1");
        assertThat(headers).containsEntry("x-gateway-replay", "false");
        // PASSTHROUGH: 제공자 서명 헤더가 그대로 간다. 소비자의 기존 검증 코드가 계속 돈다(설계 9.5).
        assertThat(headers).containsEntry("x-hub-signature-256", "sha256=deadbeef");

        var attempts = events.findAttempts(eventId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(attempts.get(0).failureClass()).isEqualTo(FailureClass.SUCCESS);
        assertThat(attempts.get(0).responseStatus()).isEqualTo(200);
        assertThat(attempts.get(0).backoffMs()).isNull();
    }

    @Test
    @DisplayName("5xx 는 재시도로 예약되고 백오프만큼 미래로 밀린다")
    void retriesOnServerError() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");
        scenario = n -> 500;

        var before = java.time.Instant.now();
        runOneCycle();

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.PENDING);
        assertThat(event.attemptCount()).isEqualTo(1);
        assertThat(event.lastBackoffMs()).isNotNull();
        assertThat(event.nextAttemptAt()).isAfterOrEqualTo(before);

        var attempt = events.findAttempts(eventId).get(0);
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.RETRY);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.HTTP_5XX);
        // 시도 이력의 대기 시간이 이벤트에 실제로 걸린 대기 시간과 같아야 지터 비교(A-2)에 쓸 수 있다.
        assertThat(attempt.backoffMs()).isEqualTo(event.lastBackoffMs());
    }

    @Test
    @DisplayName("처음 2회 실패 후 성공하면 최종적으로 DELIVERED 이고 시도 이력이 3건 남는다")
    void succeedsAfterRetries() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");
        scenario = n -> n <= 2 ? 503 : 200;

        for (int i = 0; i < 3; i++) {
            // 백오프가 걸려 next_attempt_at 이 미래다. 시간을 기다리는 대신 앞으로 당긴다.
            jdbc.sql("UPDATE event SET next_attempt_at = NOW(3) WHERE id = :id").param("id", eventId).update();
            runOneCycle();
        }

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.DELIVERED);
        assertThat(event.attemptCount()).isEqualTo(3);

        var attempts = events.findAttempts(eventId);
        assertThat(attempts).hasSize(3);
        assertThat(attempts.stream().map(a -> a.outcome().name()).toList())
                .containsExactly("RETRY", "RETRY", "DELIVERED");
    }

    @Test
    @DisplayName("4xx 는 재시도하지 않고 즉시 DEAD")
    void deadOnClientError() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");
        scenario = n -> 400;

        runOneCycle();

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.DEAD);
        // 시도 한도가 8인데도 한 번 만에 끝난다 — 재시도해도 결과가 같기 때문이다(설계 8.1.1).
        assertThat(event.attemptCount()).isEqualTo(1);
        var attempt = events.findAttempts(eventId).get(0);
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.DEAD);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.HTTP_4XX);
        assertThat(attempt.backoffMs()).isNull();
    }

    @Test
    @DisplayName("429 는 4xx 지만 재시도한다 — 스로틀링은 영구 실패가 아니다")
    void retriesOnTooManyRequests() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");
        scenario = n -> 429;

        runOneCycle();

        assertThat(events.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.PENDING);
        // 원인은 4xx 인데 판정은 재시도다. outcome 과 failure_class 를 한 컬럼으로 합쳤다면 이 줄을 쓸 수 없다.
        var attempt = events.findAttempts(eventId).get(0);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.HTTP_4XX);
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.RETRY);
    }

    @Test
    @DisplayName("최대 시도를 소진하면 DEAD 로 떨어진다")
    void deadAfterMaxAttempts() {
        long endpointId = registerEndpoint(3);
        long eventId = enqueue(endpointId, "d-1");
        scenario = n -> 500;

        for (int i = 0; i < 3; i++) {
            jdbc.sql("UPDATE event SET next_attempt_at = NOW(3) WHERE id = :id").param("id", eventId).update();
            runOneCycle();
        }

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.DEAD);
        assertThat(event.attemptCount()).isEqualTo(3);
        var attempts = events.findAttempts(eventId);
        assertThat(attempts).hasSize(3);
        // 원인은 세 번 다 5xx 이고 판정만 마지막에 DEAD 로 바뀐다.
        var last = attempts.get(2);
        assertThat(last.outcome()).isEqualTo(DeliveryOutcome.DEAD);
        assertThat(last.failureClass()).isEqualTo(FailureClass.HTTP_5XX);
        assertThat(last.backoffMs()).isNull();
    }

    @Test
    @DisplayName("소비자가 아예 안 떠 있으면(커넥션 거부) 재시도로 넘어간다")
    void retriesWhenConsumerIsDown() {
        // 아무도 듣지 않는 포트. 배포 중 파드가 내려간 상황과 같다(설계 8.1.1 첫 줄).
        long endpointId = endpoints.insert("down", "github", "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8),
                "http://localhost:1/consume", SignatureMode.PASSTHROUGH, 8, 7);
        long eventId = enqueue(endpointId, "d-1");

        runOneCycle();

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.PENDING);
        var attempt = events.findAttempts(eventId).get(0);
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.RETRY);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.CONN_REFUSED);
        assertThat(attempt.responseStatus()).isNull();
        assertThat(attempt.errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("호스트 이름이 안 풀리면 DNS_FAIL — 최상위 예외는 커넥션 거부와 똑같은 ConnectException 이다")
    void classifiesDnsFailure() {
        // .invalid 는 절대 해석되지 않도록 예약된 TLD 다(RFC 2606).
        long endpointId = endpoints.insert("nowhere", "github", "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8),
                "http://consumer.invalid/consume", SignatureMode.PASSTHROUGH, 8, 7);
        long eventId = enqueue(endpointId, "d-1");

        runOneCycle();

        var attempt = events.findAttempts(eventId).get(0);
        assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.RETRY);
        assertThat(attempt.failureClass()).isEqualTo(FailureClass.DNS_FAIL);
    }

    @Test
    @DisplayName("요청을 받고 응답 없이 끊으면 CONN_RESET — 소비자 응답 드롭이 게이트웨이에는 이렇게 보인다")
    void classifiesDroppedConnection() throws IOException {
        try (var dropper = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket s = dropper.accept()) {
                    // 요청을 읽고(소비자라면 여기서 처리를 끝냈을 것이다) 응답 없이 닫는다.
                    s.getInputStream().read(new byte[8192]);
                } catch (IOException ignored) {
                }
            });
            long endpointId = endpoints.insert("dropper", "github", "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8),
                    "http://localhost:" + dropper.getLocalPort() + "/consume", SignatureMode.PASSTHROUGH, 8, 7);
            long eventId = enqueue(endpointId, "d-1");

            runOneCycle();

            var attempt = events.findAttempts(eventId).get(0);
            assertThat(attempt.outcome()).isEqualTo(DeliveryOutcome.RETRY);
            assertThat(attempt.failureClass()).isEqualTo(FailureClass.CONN_RESET);
            assertThat(attempt.responseStatus()).isNull();
        }
    }

    @Test
    @DisplayName("claimBatch 는 PENDING 만 집고, 집은 건은 다른 워커가 다시 집지 못한다")
    void claimIsExclusive() {
        long endpointId = registerEndpoint(8);
        enqueue(endpointId, "d-1");
        enqueue(endpointId, "d-2");

        assertThat(events.claimBatch(50)).hasSize(2);
        // 이미 DELIVERING 이므로 두 번째 폴링에는 안 잡힌다.
        assertThat(events.claimBatch(50)).isEmpty();
    }

    @Test
    @DisplayName("DELIVERING 인 채 죽은 행은 좀비 회수가 PENDING 으로 되돌린다")
    void reclaimsZombies() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");

        // 전달 직전에 프로세스가 죽은 상태를 만든다.
        events.claimBatch(50);
        jdbc.sql("UPDATE event SET updated_at = DATE_SUB(NOW(3), INTERVAL 10 MINUTE) WHERE id = :id")
                .param("id", eventId).update();

        assertThat(events.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.DELIVERING);
        assertThat(events.reclaimZombies(Duration.ofMinutes(5))).isEqualTo(1);

        var event = events.findById(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo(EventStatus.PENDING);
        // 회수 후 다시 전달된다. 소비자가 이미 처리했다면 중복이고, 소비자 멱등성이 받아낸다(설계 7.2.1).
        runOneCycle();
        assertThat(events.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.DELIVERED);
    }

    @Test
    @DisplayName("아직 시간이 안 된 재시도는 집지 않는다")
    void respectsNextAttemptAt() {
        long endpointId = registerEndpoint(8);
        long eventId = enqueue(endpointId, "d-1");
        jdbc.sql("UPDATE event SET next_attempt_at = DATE_ADD(NOW(3), INTERVAL 1 HOUR) WHERE id = :id")
                .param("id", eventId).update();

        assertThat(events.claimBatch(50)).isEmpty();
    }
}
