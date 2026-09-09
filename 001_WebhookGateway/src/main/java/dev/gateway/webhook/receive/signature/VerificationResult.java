package dev.gateway.webhook.receive.signature;

/**
 * 검증 결과. 실패 사유는 로그와 관리 API 에만 쓰고 제공자에게는 돌려주지 않는다
 * (어떤 바이트가 틀렸는지 알려주면 공격자에게 힌트가 된다).
 */
public record VerificationResult(boolean valid, String reason) {

    private static final VerificationResult OK = new VerificationResult(true, null);

    public static VerificationResult ok() {
        return OK;
    }

    public static VerificationResult fail(String reason) {
        return new VerificationResult(false, reason);
    }
}
