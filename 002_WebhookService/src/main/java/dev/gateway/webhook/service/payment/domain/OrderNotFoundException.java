package dev.gateway.webhook.service.payment.domain;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long orderId) {
        super("주문을 찾을 수 없습니다. orderId=" + orderId);
    }
}
