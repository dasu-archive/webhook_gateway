package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.ledger.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 전달부 폴링 루프. 설계 5.1의 {@code @Scheduled} 워커가 이것이다.
 *
 * <p>한 배치를 전부 끝낸 뒤에 다음 폴링을 돈다({@code fixedDelay}).
 * 이렇게 해야 인플라이트 건수가 batch-size 를 넘지 않아 소비자에 대한 압력이 예측 가능해진다.
 */
@Component
@ConditionalOnProperty(prefix = "gateway.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

    private final EventRepository events;
    private final EventDispatcher dispatcher;
    private final ExecutorService executor;
    private final GatewayProperties properties;

    public DispatchWorker(EventRepository events, EventDispatcher dispatcher,
                          ExecutorService executor, GatewayProperties properties) {
        this.events = events;
        this.dispatcher = dispatcher;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gateway.dispatch.poll-interval:500ms}")
    public void poll() {
        try {
            List<Long> claimed = events.claimBatch(properties.getDispatch().getBatchSize());
            if (claimed.isEmpty()) {
                return;
            }
            log.debug("{}건 집음", claimed.size());

            CompletableFuture.allOf(claimed.stream()
                            .map(id -> CompletableFuture.runAsync(() -> dispatchQuietly(id), executor))
                            .toArray(CompletableFuture[]::new))
                    .join();
        } catch (Exception e) {
            // 폴링 루프는 어떤 예외로도 멈추면 안 된다. 멈추면 큐가 조용히 쌓인다.
            log.error("폴링 사이클 실패", e);
        }
    }

    /**
     * 한 건이 터져도 배치의 나머지는 진행한다.
     * 여기서 예외가 새면 그 이벤트는 DELIVERING 인 채로 남고, 좀비 회수 배치가 주워간다(설계 7.2.1).
     */
    private void dispatchQuietly(long eventId) {
        try {
            dispatcher.dispatch(eventId);
        } catch (Exception e) {
            log.error("이벤트 {} 전달 중 예외. 좀비 회수에 맡긴다", eventId, e);
        }
    }
}
