package dev.gateway.webhook.ledger;

import java.time.Instant;

public record Endpoint(
        long id,
        String slug,
        String provider,
        byte[] secretCurrent,
        byte[] secretPrevious,
        Instant secretRotatedAt,
        String targetUrl,
        SignatureMode signatureMode,
        int maxAttempts,
        int retentionDays,
        boolean enabled,
        /** 재생 가드 4번. 소비자가 X-Gateway-Event-Id 로 멱등 처리한다고 선언했는가 */
        boolean idempotencyConfirmed,
        Instant createdAt
) {
    /**
     * 구 시크릿을 아직 받아줄지. 설계 6.2 "시크릿 로테이션".
     * 유예 기간이 지나면 구 시크릿으로 서명된 재시도는 더 이상 통과하지 않는다.
     */
    public boolean previousSecretUsable(Instant now, java.time.Duration grace) {
        return secretPrevious != null
                && secretRotatedAt != null
                && now.isBefore(secretRotatedAt.plus(grace));
    }
}
