package dev.gateway.webhook.receive;

/**
 * 수신 결과와 그에 대응하는 HTTP 응답. 설계 6.4.
 *
 * <p>응답 코드 선택은 전부 "제공자의 재시도를 유도할 것인가"로 결정된다.
 */
public sealed interface ReceiveOutcome {

    /** 저장 성공. 200 */
    record Accepted(long eventId) implements ReceiveOutcome {
    }

    /** 이미 받은 이벤트. 200 — 재시도를 멈추게 한다. */
    record Duplicate(String idempotencyKey) implements ReceiveOutcome {
    }

    /** 401. 재시도해도 결과가 같다. */
    record InvalidSignature(String reason) implements ReceiveOutcome {
    }

    /** 404 */
    record UnknownEndpoint() implements ReceiveOutcome {
    }

    /** 413 */
    record TooLarge(int size, int limit) implements ReceiveOutcome {
    }

    /** 500. 우리 설정 문제이므로 재시도를 받는 편이 낫다. */
    record Misconfigured(String provider) implements ReceiveOutcome {
    }

    static ReceiveOutcome accepted(long eventId) {
        return new Accepted(eventId);
    }

    static ReceiveOutcome duplicate(String key) {
        return new Duplicate(key);
    }

    static ReceiveOutcome invalidSignature(String reason) {
        return new InvalidSignature(reason);
    }

    static ReceiveOutcome unknownEndpoint() {
        return new UnknownEndpoint();
    }

    static ReceiveOutcome tooLarge(int size, int limit) {
        return new TooLarge(size, limit);
    }

    static ReceiveOutcome misconfigured(String provider) {
        return new Misconfigured(provider);
    }
}
