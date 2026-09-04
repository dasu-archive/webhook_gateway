package dev.gateway.webhook.receive;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 수신부. 설계 5.1 — 컨트롤러 하나가 전부다.
 *
 * <p>{@code byte[]} 로 받는 것이 핵심이다. {@code @RequestBody PaymentEvent} 로 받으면
 * Jackson 이 스트림을 소비하고 재직렬화된 바디는 원본과 바이트가 다르다.
 * HMAC 은 바이트 하나만 달라도 깨지므로 그 순간 서명 검증이 원천적으로 불가능해진다(설계 6.1).
 */
@RestController
public class WebhookReceiveController {

    static final String EVENT_ID_HEADER = "X-Gateway-Event-Id";

    private final ReceiveService receiveService;

    public WebhookReceiveController(ReceiveService receiveService) {
        this.receiveService = receiveService;
    }

    @PostMapping("/webhooks/{slug}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String slug,
                                                       @RequestBody(required = false) byte[] body,
                                                       @RequestHeader HttpHeaders headers) {
        byte[] rawBody = body == null ? new byte[0] : body;
        ReceiveOutcome outcome = receiveService.receive(slug, rawBody, headers);

        return switch (outcome) {
            case ReceiveOutcome.Accepted a -> ResponseEntity.ok()
                    .header(EVENT_ID_HEADER, String.valueOf(a.eventId()))
                    .body(Map.of("status", "accepted", "eventId", a.eventId()));

            // 이미 받았음을 알려 제공자의 재시도를 멈추게 한다.
            case ReceiveOutcome.Duplicate d -> ResponseEntity.ok()
                    .body(Map.of("status", "duplicate", "idempotencyKey", d.idempotencyKey()));

            // 사유는 돌려주지 않는다. 어디가 틀렸는지 알려주면 공격자에게 힌트가 된다.
            case ReceiveOutcome.InvalidSignature ignored -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "invalid_signature"));

            case ReceiveOutcome.UnknownEndpoint ignored -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "unknown_endpoint", "slug", slug));

            case ReceiveOutcome.TooLarge t -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("status", "payload_too_large", "size", t.size(), "limit", t.limit()));

            // 우리 설정 문제다. 500 을 줘서 제공자 재시도를 받는 편이 유실보다 낫다.
            case ReceiveOutcome.Misconfigured m -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "misconfigured", "provider", m.provider()));
        };
    }
}
