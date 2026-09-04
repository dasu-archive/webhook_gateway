package dev.gateway.webhook.ledger;

import java.time.Instant;
import java.util.Map;

public record Event(
        long id,
        long endpointId,
        String idempotencyKey,
        IdempotencySource idempotencySource,
        byte[] rawBody,
        Map<String, String> rawHeaders,
        String contentType,
        int bodySize,
        EventStatus status,
        int attemptCount,
        Integer lastBackoffMs,
        Instant nextAttemptAt,
        Instant receivedAt,
        Instant updatedAt,
        Long replayOf
) {}
