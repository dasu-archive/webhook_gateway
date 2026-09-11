package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.common.HeaderPolicy;
import dev.gateway.webhook.ledger.DeliveryOutcome;
import dev.gateway.webhook.ledger.Endpoint;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.Event;
import dev.gateway.webhook.ledger.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 이벤트 한 건을 소비자에게 전달하고 결과를 원장에 기록한다. 설계 8절.
 *
 * <p>전달 보장은 at-least-once 다(P3). 게이트웨이가 제공자 쪽 중복을 걸러내도
 * 여기서 소비자로 가는 전달은 여전히 중복될 수 있고, 소비자가
 * {@code X-Gateway-Event-Id} 로 다시 걸러야 한다(설계 7.6).
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    public static final String EVENT_ID_HEADER = "X-Gateway-Event-Id";
    public static final String ENDPOINT_HEADER = "X-Gateway-Endpoint";
    public static final String ATTEMPT_HEADER = "X-Gateway-Attempt";
    public static final String RECEIVED_AT_HEADER = "X-Gateway-Received-At";
    public static final String REPLAY_HEADER = "X-Gateway-Replay";
    public static final String IDEMPOTENCY_SOURCE_HEADER = "X-Gateway-Idempotency-Source";

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final BackoffCalculator backoff;
    private final HttpClient httpClient;
    private final GatewayProperties properties;
    private final Clock clock;

    public EventDispatcher(EventRepository events, EndpointRepository endpoints,
                           BackoffCalculator backoff, HttpClient httpClient,
                           GatewayProperties properties, Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.backoff = backoff;
        this.httpClient = httpClient;
        this.properties = properties;
        this.clock = clock;
    }

    public void dispatch(long eventId) {
        Event event = events.findById(eventId).orElse(null);
        if (event == null) {
            log.warn("전달 대상 이벤트 {} 가 사라졌다", eventId);
            return;
        }
        Endpoint endpoint = endpoints.findById(event.endpointId()).orElse(null);
        if (endpoint == null) {
            log.error("이벤트 {} 의 엔드포인트 {} 가 없다. DEAD 로 보낸다", eventId, event.endpointId());
            fail(event, null, null, "엔드포인트 소실", 1, Instant.now(clock), true);
            return;
        }

        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    buildRequest(event, endpoint), HttpResponse.BodyHandlers.ofByteArray());
            long durationMs = elapsedMs(startNanos);
            handleResponse(event, endpoint, response.statusCode(), durationMs, startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 종료 중이다. 상태를 되돌려 다음 사이클(또는 좀비 회수)이 다시 집게 한다.
            events.scheduleRetry(event.id(), clock.instant(), 0);
        } catch (Exception e) {
            // 커넥션 거부, 타임아웃, DNS 실패. 전부 "소비자 사정이 잠깐 나쁜 것"이라 재시도가 유효하다(설계 8.1.1).
            long durationMs = elapsedMs(startNanos);
            fail(event, endpoint, null, describe(e), durationMs, startedAt, false);
        }
    }

    private HttpRequest buildRequest(Event event, Endpoint endpoint) {
        var builder = HttpRequest.newBuilder(URI.create(endpoint.targetUrl()))
                .timeout(properties.getDispatch().getRequestTimeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(event.rawBody()));

        // PASSTHROUGH: 제공자의 원본 헤더를 그대로 넘긴다. 소비자의 기존 서명 검증 코드를
        // 건드리지 않고 도입하기 위해서다(설계 9.5). 대신 타임스탬프가 서명에 들어가는 제공자(standard)는
        // 재생하면 소비자의 윈도우 검사에 걸린다. GitHub 은 타임스탬프가 없어 PASSTHROUGH 로도 재생된다.
        event.rawHeaders().forEach((name, value) -> {
            if (HeaderPolicy.forwardable(name)) {
                builder.header(name, value);
            }
        });

        builder.header("Content-Type",
                event.contentType() == null ? "application/octet-stream" : event.contentType());
        builder.header(EVENT_ID_HEADER, String.valueOf(event.id()));
        builder.header(ENDPOINT_HEADER, endpoint.slug());
        builder.header(ATTEMPT_HEADER, String.valueOf(event.attemptCount()));
        builder.header(RECEIVED_AT_HEADER, DateTimeFormatter.ISO_INSTANT.format(event.receivedAt()));
        builder.header(IDEMPOTENCY_SOURCE_HEADER, event.idempotencySource().name());
        builder.header(REPLAY_HEADER, String.valueOf(event.replayOf() != null));
        return builder.build();
    }

    private void handleResponse(Event event, Endpoint endpoint, int status, long durationMs, Instant startedAt) {
        if (status >= 200 && status < 300) {
            events.recordAttempt(event.id(), event.attemptCount(), startedAt, durationMs,
                    status, DeliveryOutcome.DELIVERED, null);
            events.markDelivered(event.id());
            log.debug("전달 성공 event={} status={} {}ms", event.id(), status, durationMs);
            return;
        }

        // 설계 8.1.1의 판정 규칙: 4xx 는 재시도해도 결과가 같으므로 즉시 DEAD.
        // 다만 408/429 는 "지금은 안 된다"는 뜻이라 예외로 둔다. 이 두 개를 DEAD 로 보내면
        // 소비자가 스로틀링만 해도 이벤트를 잃는다.
        boolean permanent = status >= 400 && status < 500 && status != 408 && status != 429;
        fail(event, endpoint, status, "소비자 응답 " + status, durationMs, startedAt, permanent);
    }

    private void fail(Event event, Endpoint endpoint, Integer status, String error,
                      long durationMs, Instant startedAt, boolean permanent) {
        int maxAttempts = endpoint == null ? 1 : endpoint.maxAttempts();
        boolean exhausted = event.attemptCount() >= maxAttempts;

        if (permanent || exhausted) {
            events.recordAttempt(event.id(), event.attemptCount(), startedAt, durationMs,
                    status, DeliveryOutcome.DEAD, error);
            events.markDead(event.id());
            // 데드레터 진입 자체가 알림 이벤트다(설계 8.5). 알림 없는 데드레터는 아무도 보지 않는 무덤이 된다.
            log.error("DEAD event={} endpoint={} attempts={}/{} 사유={} ({})",
                    event.id(), endpoint == null ? "?" : endpoint.slug(),
                    event.attemptCount(), maxAttempts, error,
                    permanent ? "영구 실패" : "재시도 소진");
            return;
        }

        Duration delay = backoff.next(event.attemptCount(), event.lastBackoffMs());
        events.recordAttempt(event.id(), event.attemptCount(), startedAt, durationMs,
                status, DeliveryOutcome.RETRY, error);
        events.scheduleRetry(event.id(), clock.instant().plus(delay), delay.toMillis());
        log.debug("재시도 예약 event={} attempt={}/{} delay={}ms 사유={}",
                event.id(), event.attemptCount(), maxAttempts, delay.toMillis(), error);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String describe(Exception e) {
        var cause = e.getCause();
        var detail = e.getMessage() == null && cause != null ? cause.getMessage() : e.getMessage();
        return e.getClass().getSimpleName() + (detail == null ? "" : ": " + detail);
    }
}
