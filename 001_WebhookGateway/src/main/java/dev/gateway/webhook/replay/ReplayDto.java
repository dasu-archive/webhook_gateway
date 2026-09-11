package dev.gateway.webhook.replay;

import dev.gateway.webhook.ledger.Replay;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 재생 API DTO. 범위는 [receivedFrom, receivedTo) 이고 둘 다 생략할 수 있다. */
public final class ReplayDto {

    private ReplayDto() {
    }

    public record ScopeRequest(
            @NotBlank String endpoint,
            Instant receivedFrom,
            Instant receivedTo
    ) {
    }

    /**
     * @param expectedCount 드라이런의 eligible. 실행 시점 건수가 이것과 다르면 아무것도 바꾸지 않고 409.
     *                      범위를 잘못 잡은 재생은 되돌릴 수 없으므로 사람이 본 숫자로 한 번 더 묶는다
     */
    public record ExecuteRequest(
            @NotBlank String endpoint,
            Instant receivedFrom,
            Instant receivedTo,
            @NotNull @Min(1) Integer expectedCount,
            @NotBlank @Size(max = 64) String requestedBy,
            @NotBlank @Size(max = 255) String reason
    ) {
    }

    /**
     * @param matched  범위 안의 DEAD 전부
     * @param eligible 그중 재생할 것. 실행 요청의 expectedCount 로 그대로 넘긴다
     * @param skipped  제외 사유별 건수 — 가드 1·2번
     * @param guards   엔드포인트 단위 가드 — 3·4번
     */
    public record DryRunView(
            String endpoint,
            String targetUrl,
            String status,
            Instant receivedFrom,
            Instant receivedTo,
            long matched,
            long eligible,
            Map<String, Long> skipped,
            List<Long> sampleEventIds,
            Guards guards,
            boolean executable
    ) {
    }

    public record Guards(
            boolean idempotencyConfirmed,
            boolean consumerHealthy,
            String consumerProbe
    ) {
    }

    public record ReplayView(
            long id,
            String endpoint,
            Instant receivedFrom,
            Instant receivedTo,
            String requestedBy,
            String reason,
            int eventCount,
            Instant createdAt
    ) {
        public static ReplayView of(Replay r) {
            return new ReplayView(r.id(), r.endpointSlug(), r.receivedFrom(), r.receivedTo(),
                    r.requestedBy(), r.reason(), r.eventCount(), r.createdAt());
        }
    }

    /** @param eventsByStatus 이 재생으로 옮긴 이벤트의 현재 상태. 재생이 실제로 소진됐는지 여기서 본다 */
    public record ReplayDetailView(
            ReplayView replay,
            Map<String, Long> eventsByStatus
    ) {
    }
}
