package dev.gateway.webhook.service.chaos;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 응답 드롭률. 결과표(README 1.2)의 "소비자 응답 드롭 30%" 줄을 만드는 장치다.
 *
 * <p>드롭은 "처리는 끝났는데 200 이 게이트웨이에 닿지 못한" 상황이다. 게이트웨이는 이걸 실패와
 * 구별할 수 없어 다시 보내고, 같은 X-Gateway-Event-Id 라 소비자 멱등이 결제를 건너뛴다.
 * 중복 전달은 N 인데 중복 처리는 0 인 줄이 여기서 나온다. 게이트웨이가 막을 수 없는 지점이다.
 *
 * <p>{@code webhook.chaos.enabled=true} 일 때만 뜬다. 기본은 꺼져 있다.
 */
@Component
@ConditionalOnProperty(prefix = "webhook.chaos", name = "enabled", havingValue = "true")
public class ResponseDropPolicy {

    private volatile double rate;
    private final AtomicLong dropped = new AtomicLong();

    public ResponseDropPolicy(@Value("${webhook.chaos.response-drop-rate:0}") double rate) {
        setRate(rate);
    }

    /** 이번 응답을 끊을지. 2xx 에만 묻는다. */
    public boolean shouldDrop() {
        double current = rate;
        return current > 0 && ThreadLocalRandom.current().nextDouble() < current;
    }

    /** 드롭률을 바꾸고 드롭 횟수를 0 으로 되돌린다. 시나리오 하나를 새로 시작한다는 뜻이다. */
    public void setRate(double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("드롭률은 0 이상 1 이하여야 한다: " + rate);
        }
        this.rate = rate;
        dropped.set(0);
    }

    public double rate() {
        return rate;
    }

    /** 게이트웨이의 CONN_RESET 건수와 맞춰 보는 대조값이다. */
    public long dropped() {
        return dropped.get();
    }

    void recordDrop() {
        dropped.incrementAndGet();
    }
}
