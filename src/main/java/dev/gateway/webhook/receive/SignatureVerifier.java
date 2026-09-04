package dev.gateway.webhook.receive;

import org.springframework.http.HttpHeaders;

import java.util.Optional;

/**
 * 제공자별 서명/식별 형식 차이를 흡수하는 전략. 설계 6.3.
 *
 * <p>구현체는 반드시 <b>원본 바이트</b> 위에서 검증한다. DTO 로 받아 재직렬화한 바디로는
 * HMAC 이 애초에 맞을 수 없다(설계 2.4, 6.1).
 */
public interface SignatureVerifier {

    /** endpoint.provider 컬럼에 저장되는 값. 대소문자 구분 없이 매칭된다. */
    String provider();

    VerificationResult verify(byte[] rawBody, HttpHeaders headers, EndpointSecrets secrets);

    /**
     * 제공자가 주는 이벤트 ID. 설계 7.3.
     * 비어 있으면 호출자가 바디 해시로 대체한다 — 근사치이며 정상 중복을 오판할 수 있다.
     */
    Optional<String> extractEventId(HttpHeaders headers);
}
