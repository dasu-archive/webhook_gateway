package dev.gateway.webhook;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.receive.EndpointSecrets;
import dev.gateway.webhook.receive.GithubSignatureVerifier;
import dev.gateway.webhook.receive.Hmac;
import dev.gateway.webhook.receive.StandardWebhookSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

    private static final byte[] SECRET = "topsecret-secret-1234".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"orderId\": 123, \"status\": \"DONE\"}".getBytes(StandardCharsets.UTF_8);

    private final GithubSignatureVerifier github = new GithubSignatureVerifier();

    @Test
    @DisplayName("GitHub: 올바른 서명은 통과한다")
    void githubValid() {
        var headers = new HttpHeaders();
        headers.add(GithubSignatureVerifier.SIGNATURE_HEADER, "sha256=" + Hmac.hexSha256(SECRET, BODY));

        assertThat(github.verify(BODY, headers, EndpointSecrets.of(SECRET)).valid()).isTrue();
    }

    @Test
    @DisplayName("GitHub: 바디가 1바이트만 달라도 서명이 깨진다")
    void githubRejectsAlteredBody() {
        var headers = new HttpHeaders();
        headers.add(GithubSignatureVerifier.SIGNATURE_HEADER, "sha256=" + Hmac.hexSha256(SECRET, BODY));

        // 공백 하나를 지운 바디. 눈으로는 같은 JSON 이지만 다른 바이트다 — 설계 2.4 의 핵심.
        byte[] reserialized = "{\"orderId\":123,\"status\":\"DONE\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(github.verify(reserialized, headers, EndpointSecrets.of(SECRET)).valid()).isFalse();
    }

    @Test
    @DisplayName("GitHub: 서명 헤더가 없으면 실패한다")
    void githubMissingHeader() {
        assertThat(github.verify(BODY, new HttpHeaders(), EndpointSecrets.of(SECRET)).valid()).isFalse();
    }

    @Test
    @DisplayName("로테이션: 구 시크릿으로 서명된 재시도도 유예 기간에는 통과한다")
    void previousSecretAccepted() {
        byte[] oldSecret = "old-secret-0000000000".getBytes(StandardCharsets.UTF_8);
        byte[] newSecret = "new-secret-1111111111".getBytes(StandardCharsets.UTF_8);

        var headers = new HttpHeaders();
        headers.add(GithubSignatureVerifier.SIGNATURE_HEADER, "sha256=" + Hmac.hexSha256(oldSecret, BODY));

        assertThat(github.verify(BODY, headers, new EndpointSecrets(newSecret, oldSecret)).valid()).isTrue();
        // 유예가 끝나면 후보에서 빠지고 더 이상 통과하지 않는다.
        assertThat(github.verify(BODY, headers, new EndpointSecrets(newSecret, null)).valid()).isFalse();
    }

    @Test
    @DisplayName("GitHub: X-GitHub-Delivery 를 멱등키로 쓴다")
    void githubEventId() {
        var headers = new HttpHeaders();
        headers.add(GithubSignatureVerifier.DELIVERY_HEADER, "72d3162e-cc78-11e3-81ab-4c9367dc0958");

        assertThat(github.extractEventId(headers)).contains("72d3162e-cc78-11e3-81ab-4c9367dc0958");
        assertThat(github.extractEventId(new HttpHeaders())).isEmpty();
    }

    @Test
    @DisplayName("Standard: 타임스탬프가 윈도우 안이면 통과한다")
    void standardValid() {
        var now = Instant.parse("2026-09-04T00:00:00Z");
        var verifier = standardVerifier(now);
        var headers = standardHeaders("evt_1", now.getEpochSecond());

        assertThat(verifier.verify(BODY, headers, EndpointSecrets.of(SECRET)).valid()).isTrue();
    }

    @Test
    @DisplayName("Standard: 오래된 타임스탬프는 거부한다 — 재전송 공격 방어")
    void standardRejectsStaleTimestamp() {
        var now = Instant.parse("2026-09-04T00:00:00Z");
        var verifier = standardVerifier(now);
        // 10분 전에 가로챈 유효 요청을 지금 그대로 재전송한 상황. 서명 자체는 여전히 맞는다.
        var headers = standardHeaders("evt_1", now.minusSeconds(600).getEpochSecond());

        var result = verifier.verify(BODY, headers, EndpointSecrets.of(SECRET));
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("타임스탬프 윈도우");
    }

    @Test
    @DisplayName("Standard: 서명 대상에 id 와 timestamp 가 포함되므로 id 를 바꾸면 깨진다")
    void standardBindsIdAndTimestamp() {
        var now = Instant.parse("2026-09-04T00:00:00Z");
        var verifier = standardVerifier(now);
        var headers = standardHeaders("evt_1", now.getEpochSecond());
        headers.set(StandardWebhookSignatureVerifier.ID_HEADER, "evt_tampered");

        assertThat(verifier.verify(BODY, headers, EndpointSecrets.of(SECRET)).valid()).isFalse();
    }

    private static StandardWebhookSignatureVerifier standardVerifier(Instant now) {
        return new StandardWebhookSignatureVerifier(
                new GatewayProperties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static HttpHeaders standardHeaders(String id, long epochSeconds) {
        byte[] signed = (id + "." + epochSeconds + "." + new String(BODY, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        var headers = new HttpHeaders();
        headers.add(StandardWebhookSignatureVerifier.ID_HEADER, id);
        headers.add(StandardWebhookSignatureVerifier.TIMESTAMP_HEADER, String.valueOf(epochSeconds));
        headers.add(StandardWebhookSignatureVerifier.SIGNATURE_HEADER,
                "v1," + Base64.getEncoder().encodeToString(Hmac.sha256(SECRET, signed)));
        return headers;
    }
}
