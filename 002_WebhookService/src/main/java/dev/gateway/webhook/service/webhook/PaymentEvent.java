package dev.gateway.webhook.service.webhook;

import jakarta.validation.constraints.NotNull;

/**
 * 게이트웨이가 전달하는 결제 이벤트 페이로드.
 *
 * <p>orderId 가 없는 요청은 다시 보내도 똑같이 잘못된 요청이다. 400 으로 돌려주어
 * 게이트웨이가 재시도하지 않고 즉시 데드레터로 보내게 한다.
 */
public record PaymentEvent(@NotNull Long orderId, int amount) {
}
