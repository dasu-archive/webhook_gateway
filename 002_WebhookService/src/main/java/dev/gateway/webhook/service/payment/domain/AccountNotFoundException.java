package dev.gateway.webhook.service.payment.domain;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long accountId) {
        super("계좌를 찾을 수 없습니다. accountId=" + accountId);
    }
}
