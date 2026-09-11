package dev.gateway.webhook.admin;

import dev.gateway.webhook.ledger.DeliveryAttempt;
import dev.gateway.webhook.ledger.Endpoint;
import dev.gateway.webhook.ledger.Event;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** 관리 평면 DTO. 시크릿은 어떤 응답에도 실리지 않는다. */
public final class AdminDto {

    private AdminDto() {
    }

    public record CreateEndpointRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}[a-z0-9]",
                    message = "slug 는 소문자/숫자/하이픈만 쓸 수 있다")
            String slug,

            @NotBlank String provider,

            @NotBlank @Size(min = 16, max = 255,
                    message = "시크릿은 16자 이상이어야 한다")
            String secret,

            @NotBlank @Size(max = 1024) String targetUrl,

            @Min(1) @Max(50) Integer maxAttempts,
            @Min(1) @Max(365) Integer retentionDays
    ) {
        public int maxAttemptsOrDefault() {
            return maxAttempts == null ? 12 : maxAttempts;
        }

        public int retentionDaysOrDefault() {
            return retentionDays == null ? 7 : retentionDays;
        }
    }

    public record RotateSecretRequest(
            @NotBlank @Size(min = 16, max = 255) String secret
    ) {
    }

    public record EndpointView(
            long id,
            String slug,
            String provider,
            String targetUrl,
            String signatureMode,
            int maxAttempts,
            int retentionDays,
            boolean enabled,
            boolean idempotencyConfirmed,
            boolean hasPreviousSecret,
            Instant secretRotatedAt,
            Instant createdAt
    ) {
        public static EndpointView of(Endpoint e) {
            return new EndpointView(e.id(), e.slug(), e.provider(), e.targetUrl(),
                    e.signatureMode().name(), e.maxAttempts(), e.retentionDays(), e.enabled(),
                    e.idempotencyConfirmed(), e.secretPrevious() != null, e.secretRotatedAt(), e.createdAt());
        }
    }

    public record EventView(
            long id,
            long endpointId,
            String idempotencyKey,
            String idempotencySource,
            int bodySize,
            String contentType,
            String status,
            int attemptCount,
            Integer lastBackoffMs,
            Instant nextAttemptAt,
            Instant receivedAt,
            Instant updatedAt,
            int replayCount,
            Long lastReplayId
    ) {
        public static EventView of(Event e) {
            return new EventView(e.id(), e.endpointId(), e.idempotencyKey(), e.idempotencySource().name(),
                    e.bodySize(), e.contentType(), e.status().name(), e.attemptCount(), e.lastBackoffMs(),
                    e.nextAttemptAt(), e.receivedAt(), e.updatedAt(), e.replayCount(), e.lastReplayId());
        }
    }

    /**
     * 단건 조회에만 원본이 실린다. 게이트웨이는 바디를 해석하지 않으므로(P6)
     * base64 로 그대로 돌려주고, 사람이 읽을 미리보기만 곁들인다.
     */
    public record EventDetailView(
            EventView event,
            Map<String, String> headers,
            String bodyBase64,
            String bodyPreview,
            List<AttemptView> attempts
    ) {
        private static final int PREVIEW_LIMIT = 2048;

        public static EventDetailView of(Event e, List<DeliveryAttempt> attempts) {
            var text = new String(e.rawBody(), StandardCharsets.UTF_8);
            var preview = text.length() <= PREVIEW_LIMIT
                    ? text
                    : text.substring(0, PREVIEW_LIMIT) + "...(truncated)";
            return new EventDetailView(
                    EventView.of(e),
                    e.rawHeaders(),
                    Base64.getEncoder().encodeToString(e.rawBody()),
                    preview,
                    attempts.stream().map(AttemptView::of).toList());
        }
    }

    public record AttemptView(
            int attemptNo,
            Long replayId,
            Instant startedAt,
            Integer durationMs,
            Integer responseStatus,
            String outcome,
            String failureClass,
            Integer backoffMs,
            String errorMessage
    ) {
        public static AttemptView of(DeliveryAttempt a) {
            return new AttemptView(a.attemptNo(), a.replayId(), a.startedAt(), a.durationMs(),
                    a.responseStatus(), a.outcome().name(),
                    a.failureClass() == null ? null : a.failureClass().name(),
                    a.backoffMs(), a.errorMessage());
        }
    }
}
