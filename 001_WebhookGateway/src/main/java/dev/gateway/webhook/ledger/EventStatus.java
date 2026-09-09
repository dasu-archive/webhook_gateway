package dev.gateway.webhook.ledger;

/**
 * 설계 7.2.2에 따라 FAILED 는 두지 않는다.
 * 이번 시도 실패는 DELIVERING -> PENDING 회귀로 표현하고, 최종 포기만 DEAD 다.
 */
public enum EventStatus {
    PENDING,
    DELIVERING,
    DELIVERED,
    DEAD
}
