package dev.gateway.webhook.receive;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * GitHub 형식. 설계 6.3.
 *
 * <pre>
 * X-Hub-Signature-256: sha256=&lt;hex&gt;   서명 대상은 바디 바이트 그 자체
 * X-GitHub-Delivery:   &lt;uuid&gt;          이벤트 ID
 * </pre>
 *
 * <p><b>타임스탬프가 없다.</b> 서명 대상에 시각이 들어가지 않으므로 재전송 공격
 * 윈도우를 걸 수 없다(설계 6.2의 세 논점 중 하나가 이 제공자에게는 성립하지 않는다).
 * 이 한계는 제공자 형식에서 오는 것이라 게이트웨이가 메울 수 없다.
 */
@Component
public class GithubSignatureVerifier implements SignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    public static final String DELIVERY_HEADER = "X-GitHub-Delivery";
    private static final String PREFIX = "sha256=";

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public VerificationResult verify(byte[] rawBody, HttpHeaders headers, EndpointSecrets secrets) {
        String received = headers.getFirst(SIGNATURE_HEADER);
        if (received == null || received.isBlank()) {
            return VerificationResult.fail(SIGNATURE_HEADER + " 헤더 없음");
        }
        if (!received.startsWith(PREFIX)) {
            return VerificationResult.fail("서명 접두사가 " + PREFIX + " 가 아님");
        }
        String receivedHex = received.substring(PREFIX.length());

        for (byte[] secret : secrets.candidates()) {
            if (Hmac.constantTimeEquals(receivedHex, Hmac.hexSha256(secret, rawBody))) {
                return VerificationResult.ok();
            }
        }
        return VerificationResult.fail("서명 불일치");
    }

    @Override
    public Optional<String> extractEventId(HttpHeaders headers) {
        return Optional.ofNullable(headers.getFirst(DELIVERY_HEADER))
                .filter(s -> !s.isBlank());
    }
}
