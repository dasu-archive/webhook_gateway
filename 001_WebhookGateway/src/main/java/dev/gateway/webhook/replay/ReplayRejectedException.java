package dev.gateway.webhook.replay;

/** 요청은 맞지만 지금 상태로는 재생할 수 없다. 409 로 번역된다. */
public class ReplayRejectedException extends RuntimeException {

    public enum Reason {
        /** 가드 4번. 소비자가 멱등 처리를 선언하지 않았다 */
        IDEMPOTENCY_NOT_CONFIRMED,
        /** 가드 3번. 소비자가 응답하지 않는다 */
        CONSUMER_UNHEALTHY,
        /** 드라이런에서 본 건수와 실행 시점 건수가 다르다 */
        COUNT_MISMATCH
    }

    private final Reason reason;

    public ReplayRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
