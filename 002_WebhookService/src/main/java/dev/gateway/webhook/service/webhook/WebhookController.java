package dev.gateway.webhook.service.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 게이트웨이가 전달하는 결제 웹훅의 수신 엔드포인트.
 *
 * <p>응답 코드가 곧 게이트웨이에 대한 지시다. 2xx 는 전달 완료, 4xx 는 즉시 데드레터,
 * 5xx 와 408·429 는 재시도. 상태 코드 매핑은 {@link WebhookExceptionHandler} 에 모여 있다.
 */
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookReceiver webhookReceiver;

    @PostMapping("/webhook/receiver")
    public void receiveDelivery(
            // 중복 제거의 열쇠라 필수다. 없으면 검증 실패로 400 이 나가고 게이트웨이가 이벤트를 버린다.
            @RequestHeader("X-Gateway-Event-Id") String eventId,
            // 아래 둘은 관측용이라 없어도 처리를 막지 않는다.
            @RequestHeader(value = "X-Gateway-Endpoint", required = false) String endpoint,
            @RequestHeader(value = "X-Gateway-Attempt", defaultValue = "0") int attempt,
            @Valid @RequestBody PaymentEvent payload) {

        log.info("웹훅 수신 eventId={} endpoint={} attempt={} orderId={}",
                eventId, endpoint, attempt, payload.orderId());

        webhookReceiver.receivePaymentDelivery(eventId, payload);
    }
}
