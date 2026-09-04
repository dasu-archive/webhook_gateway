package dev.gateway.webhook.ledger;

/**
 * 설계 9.5. MVP 는 PASSTHROUGH 만 지원한다.
 * RESIGN(게이트웨이 시크릿으로 재서명)은 재생 기능과 함께 v1에서 들어온다.
 */
public enum SignatureMode {
    PASSTHROUGH
}
