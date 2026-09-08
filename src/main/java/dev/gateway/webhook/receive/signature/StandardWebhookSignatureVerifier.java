package dev.gateway.webhook.receive.signature;

import dev.gateway.webhook.common.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Standard Webhooks 형식(Stripe/Svix 계열이 쓰는 타임스탬프 포함형). 설계 6.3.
 *
 * <pre>
 * webhook-id:        &lt;이벤트 ID&gt;
 * webhook-timestamp: &lt;unix seconds&gt;
 * webhook-signature: v1,&lt;base64&gt; [v1,&lt;base64&gt; ...]
 *
 * 서명 대상 = "{id}.{timestamp}.{body}"
 * </pre>
 *
 * <p>GitHub 과 달리 타임스탬프가 서명 대상에 들어가므로 재전송 공격 윈도우를 걸 수 있다.
 * 제공자 2종을 이 조합으로 고른 이유가 그것이다 — 하나는 윈도우가 있고 하나는 없다.
 *
 * <p>공개 스펙은 시크릿을 {@code whsec_} 접두사 + base64 로 표기하지만, 여기서는
 * endpoint.secret_current 에 저장된 바이트를 그대로 HMAC 키로 쓴다.
 */
@Component
public class StandardWebhookSignatureVerifier implements SignatureVerifier {

    public static final String ID_HEADER = "webhook-id";
    public static final String TIMESTAMP_HEADER = "webhook-timestamp";
    public static final String SIGNATURE_HEADER = "webhook-signature";
    private static final String VERSION = "v1,";

    private final GatewayProperties properties;
    private final Clock clock;

    public StandardWebhookSignatureVerifier(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "standard";
    }

    @Override
    public VerificationResult verify(byte[] rawBody, HttpHeaders headers, EndpointSecrets secrets) {
        String id = headers.getFirst(ID_HEADER);
        String timestamp = headers.getFirst(TIMESTAMP_HEADER);
        String signatureHeader = headers.getFirst(SIGNATURE_HEADER);

        if (id == null || timestamp == null || signatureHeader == null) {
            return VerificationResult.fail("webhook-id / webhook-timestamp / webhook-signature 중 누락");
        }

        VerificationResult window = checkTimestampWindow(timestamp);
        if (!window.valid()) {
            return window;
        }

        byte[] signedContent = (id + "." + timestamp + ".")
                .getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[signedContent.length + rawBody.length];
        System.arraycopy(signedContent, 0, message, 0, signedContent.length);
        System.arraycopy(rawBody, 0, message, signedContent.length, rawBody.length);

        var encoder = Base64.getEncoder();
        for (byte[] secret : secrets.candidates()) {
            String expected = encoder.encodeToString(Hmac.sha256(secret, message));
            for (String candidate : signatureHeader.split(" ")) {
                if (!candidate.startsWith(VERSION)) {
                    continue;
                }
                if (Hmac.constantTimeEquals(candidate.substring(VERSION.length()), expected)) {
                    return VerificationResult.ok();
                }
            }
        }
        return VerificationResult.fail("서명 불일치");
    }

    /**
     * 서명만 검증하면 가로챈 유효 요청을 나중에 그대로 재전송해도 통과한다.
     * 수신 시각과의 차이가 허용 범위를 넘으면 거부한다. 설계 6.2.
     */
    private VerificationResult checkTimestampWindow(String timestamp) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return VerificationResult.fail("webhook-timestamp 가 정수가 아님");
        }
        var tolerance = properties.getReceive().getTimestampTolerance();
        var sent = Instant.ofEpochSecond(epochSeconds);
        var now = clock.instant();
        long driftSeconds = Math.abs(now.getEpochSecond() - sent.getEpochSecond());
        if (driftSeconds > tolerance.toSeconds()) {
            return VerificationResult.fail("타임스탬프 윈도우 초과 (" + driftSeconds + "초)");
        }
        return VerificationResult.ok();
    }

    @Override
    public Optional<String> extractEventId(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(ID_HEADER))
                .filter(s -> !s.isBlank());
    }
}
