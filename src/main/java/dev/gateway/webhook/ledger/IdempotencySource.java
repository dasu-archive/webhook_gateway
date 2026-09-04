package dev.gateway.webhook.ledger;

/**
 * 멱등키를 어디서 얻었는지. 설계 7.3.
 * BODY_HASH 는 근사치다 — 같은 바디의 정상 중복을 중복으로 오판할 수 있다.
 * 사후 분석을 위해 방식을 원장에 남긴다.
 */
public enum IdempotencySource {
    PROVIDER_ID,
    BODY_HASH
}
