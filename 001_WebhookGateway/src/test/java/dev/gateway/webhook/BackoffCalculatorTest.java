package dev.gateway.webhook;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.dispatch.BackoffCalculator;
import dev.gateway.webhook.dispatch.JitterStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private static BackoffCalculator calculator(JitterStrategy jitter, Duration initial, Duration max) {
        var properties = new GatewayProperties();
        properties.getDispatch().getBackoff().setJitter(jitter);
        properties.getDispatch().getBackoff().setInitial(initial);
        properties.getDispatch().getBackoff().setMax(max);
        return new BackoffCalculator(properties);
    }

    @Test
    @DisplayName("NONE 은 initial * 2^(attempt-1) 로 자란다")
    void exponentialWithoutJitter() {
        var calculator = calculator(JitterStrategy.NONE, Duration.ofSeconds(1), Duration.ofHours(1));

        assertThat(calculator.next(1, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(calculator.next(2, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(calculator.next(3, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(calculator.next(5, null)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("지수가 커져도 max 에서 잘리고 오버플로하지 않는다")
    void cappedAtMax() {
        var calculator = calculator(JitterStrategy.NONE, Duration.ofSeconds(1), Duration.ofMinutes(10));

        assertThat(calculator.next(100, null)).isEqualTo(Duration.ofMinutes(10));
        assertThat(calculator.next(Integer.MAX_VALUE, null)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("FULL 지터는 [0, base] 안에 들어가고 한 점에 모이지 않는다")
    void fullJitterSpreads() {
        var calculator = calculator(JitterStrategy.FULL, Duration.ofSeconds(1), Duration.ofHours(1));

        var distinct = new java.util.HashSet<Long>();
        for (int i = 0; i < 200; i++) {
            long ms = calculator.next(6, null).toMillis();   // base = 32s
            assertThat(ms).isBetween(0L, 32_000L);
            distinct.add(ms);
        }
        // 재시도 시각이 흩어지는 것이 지터의 목적이다. 값이 하나뿐이면 thundering herd 가 그대로 남는다.
        assertThat(distinct).hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("DECORRELATED 는 직전 대기 시간을 기준으로 [initial, prev*3] 에서 뽑는다")
    void decorrelatedUsesPreviousDelay() {
        var calculator = calculator(JitterStrategy.DECORRELATED, Duration.ofSeconds(1), Duration.ofHours(1));

        for (int i = 0; i < 100; i++) {
            assertThat(calculator.next(3, 4_000).toMillis()).isBetween(1_000L, 12_000L);
        }
    }

    @Test
    @DisplayName("DECORRELATED 도 max 를 넘지 않는다")
    void decorrelatedRespectsMax() {
        var calculator = calculator(JitterStrategy.DECORRELATED, Duration.ofSeconds(1), Duration.ofSeconds(30));

        for (int i = 0; i < 100; i++) {
            assertThat(calculator.next(10, 60_000).toMillis()).isLessThanOrEqualTo(30_000L);
        }
    }
}
