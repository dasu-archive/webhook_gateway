package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.ledger.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좀비 회수. 설계 7.2.1.
 *
 * <p>DELIVERING 으로 바꾸고 POST 를 보낸 뒤 응답 전에 프로세스가 죽으면 그 행은
 * 영원히 DELIVERING 이다. 폴링 쿼리는 PENDING 만 보므로 아무도 다시 집지 않는다.
 *
 * <p>회수하면 소비자가 이미 처리한 것을 다시 보낼 수 있다. 또 중복이고, 또 소비자
 * 멱등성으로 푼다. reclaim-after 는 소비자 응답 타임아웃보다 충분히 커야 한다 —
 * 짧으면 정상 처리 중인 건을 중복 발송한다.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ZombieReclaimer {

    private static final Logger log = LoggerFactory.getLogger(ZombieReclaimer.class);

    private final EventRepository events;
    private final GatewayProperties properties;

    public ZombieReclaimer(EventRepository events, GatewayProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gateway.zombie.interval:1m}")
    public void reclaim() {
        try {
            int reclaimed = events.reclaimZombies(properties.getZombie().getReclaimAfter());
            if (reclaimed > 0) {
                log.warn("좀비 {}건 회수. 중복 전달이 발생할 수 있다", reclaimed);
            }
        } catch (Exception e) {
            log.error("좀비 회수 실패", e);
        }
    }
}
