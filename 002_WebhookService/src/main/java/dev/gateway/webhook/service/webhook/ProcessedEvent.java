package dev.gateway.webhook.service.webhook;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 이미 처리한 게이트웨이 이벤트의 흔적.
 *
 * <p>게이트웨이의 전달 보장은 at-least-once 다. 게이트웨이가 제공자 쪽 중복을 걸러내도
 * 게이트웨이에서 우리로 오는 구간은 여전히 중복될 수 있고(타임아웃 후 재시도가 대표적),
 * 소비자가 {@code X-Gateway-Event-Id} 로 다시 걸러야 한다. 결제는 두 번 처리되면
 * 실제 돈이 두 번 빠지므로 이 방어가 필수다.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "gateway_event_id", length = 64)
    private String gatewayEventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** JPA 전용. */
    protected ProcessedEvent() {
    }

    public ProcessedEvent(String gatewayEventId, Long orderId, Instant processedAt) {
        this.gatewayEventId = Objects.requireNonNull(gatewayEventId, "gatewayEventId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    public String getGatewayEventId() {
        return gatewayEventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
