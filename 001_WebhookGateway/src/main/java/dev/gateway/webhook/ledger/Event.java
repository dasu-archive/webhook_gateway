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
        /** 재생된 횟수. 0 이 아니면 소비자에게 X-Gateway-Replay: true 가 붙는다 */
        int replayCount,
        /** 마지막으로 이 이벤트를 옮긴 재생. 재생 뒤 시도는 이 값을 delivery_attempt.replay_id 에 남긴다 */
        Long lastReplayId
) {}
