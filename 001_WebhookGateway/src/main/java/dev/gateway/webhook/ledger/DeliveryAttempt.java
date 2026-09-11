package dev.gateway.webhook.ledger;

import java.time.Instant;

public record DeliveryAttempt(
        long id,
        long eventId,
        int attemptNo,
        /** 재생 뒤의 시도면 그 재생 id. 원래 시도는 null */
        Long replayId,
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
