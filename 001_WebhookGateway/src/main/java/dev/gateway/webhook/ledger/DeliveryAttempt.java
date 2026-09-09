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
        String errorMessage
) {}
