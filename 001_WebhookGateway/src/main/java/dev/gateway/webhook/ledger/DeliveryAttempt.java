package dev.gateway.webhook.ledger;

import java.time.Instant;

public record DeliveryAttempt(
        long id,
        long eventId,
        int attemptNo,
        Instant startedAt,
        Integer durationMs,
        Integer responseStatus,
        DeliveryOutcome outcome,
        /** V2 이전에 기록된 시도는 null */
        FailureClass failureClass,
        /** RETRY 일 때만 값이 있다 */
        Integer backoffMs,
        String errorMessage
) {}
