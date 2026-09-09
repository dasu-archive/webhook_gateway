package dev.gateway.webhook.service.webhook;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.gateway.webhook.service.payment.application.PaymentService;
import lombok.RequiredArgsConstructor;

/**
 * 게이트웨이가 전달한 결제 이벤트를 받아 중복을 걸러내고 결제로 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class WebhookReceiver {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiver.class);

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedEvents;

    /**
     * 같은 {@code gatewayEventId} 가 다시 오면 결제를 건너뛴다.
     *
     * <p>예외를 던지지 않고 조용히 돌아가는 것이 핵심이다. 중복이라고 4xx 를 주면 게이트웨이가
     * 이벤트를 데드레터로 보내고, 5xx 를 주면 영원히 재시도한다. 200 을 줘야 재시도가 멈춘다.
     *
     * <p>조회 후 삽입은 동시 요청에서 둘 다 통과할 수 있다. 그때는 PK 충돌로 트랜잭션이
     * 롤백되어 500 이 나가고, 게이트웨이가 재시도할 때 이 조회에 걸린다. 결과적으로
     * 결제는 정확히 한 번만 일어난다.
     *
     * <p>{@code @Transactional} 이 없으면 결제가 실패해도 처리 기록만 남아, 재시도가
     * 중복으로 걸러지면서 결제가 영영 유실된다.
     */
    @Transactional
    public void receivePaymentDelivery(String gatewayEventId, PaymentEvent payload) {
        if (processedEvents.existsById(gatewayEventId)) {
            log.debug("중복 이벤트를 건너뛴다 eventId={} orderId={}", gatewayEventId, payload.orderId());
            return;
        }

        processedEvents.save(new ProcessedEvent(gatewayEventId, payload.orderId(), Instant.now()));
        paymentService.processPayment(payload.orderId());
    }
}
