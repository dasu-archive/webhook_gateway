package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.common.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** 다음 재시도까지의 대기 시간. 설계 8.2. */
@Component
public class BackoffCalculator {

    private final GatewayProperties.Backoff config;

    public BackoffCalculator(GatewayProperties properties) {
        this.config = properties.getDispatch().getBackoff();
    }

    /**
     * @param attemptCount  방금 끝난 시도의 번호 (1부터)
     * @param lastBackoffMs 직전에 실제로 적용한 대기 시간. DECORRELATED 만 사용한다
     */
    public Duration next(int attemptCount, Integer lastBackoffMs) {
        long initialMs = config.getInitial().toMillis();
        long maxMs = config.getMax().toMillis();
        long baseMs = exponential(initialMs, maxMs, attemptCount);

        long chosen = switch (config.getJitter()) {
            case NONE -> baseMs;
            case FULL -> randomBetween(0, baseMs);
            case DECORRELATED -> {
                long previous = lastBackoffMs == null ? initialMs : lastBackoffMs;
                yield Math.min(maxMs, randomBetween(initialMs, Math.max(initialMs, previous * 3)));
            }
        };
        return Duration.ofMillis(Math.max(0, chosen));
    }

    /** initial * 2^(attempt-1). 오버플로 없이 max 에서 잘린다. */
    private static long exponential(long initialMs, long maxMs, int attemptCount) {
        long value = initialMs;
        for (int i = 1; i < Math.max(1, attemptCount); i++) {
            if (value >= maxMs / 2) {
                return maxMs;
            }
            value *= 2;
        }
        return Math.min(value, maxMs);
    }

    private static long randomBetween(long lowInclusive, long highInclusive) {
        if (highInclusive <= lowInclusive) {
            return lowInclusive;
        }
        return ThreadLocalRandom.current().nextLong(lowInclusive, highInclusive + 1);
    }
}
