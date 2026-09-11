package dev.gateway.webhook.ledger;

import java.time.Instant;

/** 재생 이력 한 건. 범위는 [receivedFrom, receivedTo) 이고 null 이면 열린 끝이다. */
public record Replay(
        long id,
        long endpointId,
        String endpointSlug,
        Instant receivedFrom,
        Instant receivedTo,
        String requestedBy,
        String reason,
        int eventCount,
        Instant createdAt
) {}
