package dev.gateway.webhook.service.payment.domain;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(Long accountId, int balance, int amount) {
        super("잔액이 부족합니다. accountId=" + accountId + ", balance=" + balance + ", amount=" + amount);
    }
}
