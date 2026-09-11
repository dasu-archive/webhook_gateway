package dev.gateway.webhook;

import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.EventRepository;
import dev.gateway.webhook.ledger.EventStatus;
import dev.gateway.webhook.ledger.IdempotencySource;
import dev.gateway.webhook.ledger.SignatureMode;
import dev.gateway.webhook.receive.signature.GithubSignatureVerifier;
import dev.gateway.webhook.receive.signature.Hmac;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 수신부. 설계 6절과 7.4. */
class ReceiveIntegrationTest extends IntegrationTestBase {

    /** 일부러 공백을 넣었다. 재직렬화하면 사라지는 바이트다. */
    private static final String BODY = "{\"orderId\": 123, \"status\": \"DONE\"}";
    private static final String SECRET = "topsecret-secret-1234";

    @Autowired
    WebApplicationContext context;
    @Autowired
    EndpointRepository endpoints;
    @Autowired
    EventRepository events;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long registerGithubEndpoint() {
        return endpoints.insert("gh-push", "github", SECRET.getBytes(StandardCharsets.UTF_8),
                "http://localhost:1/consume", SignatureMode.PASSTHROUGH, 8, 7);
    }

    private static String sign(String body) {
        return "sha256=" + Hmac.hmacSha256Hex(SECRET.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("서명이 맞으면 200 이고 원본 바이트가 그대로 원장에 남는다")
    void acceptsAndStoresRawBytes() throws Exception {
        long endpointId = registerGithubEndpoint();

        mockMvc().perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.DELIVERY_HEADER, "d-1")
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign(BODY))
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        var stored = events.search(endpointId, null, 10, 0);
        assertThat(stored).hasSize(1);
        var event = stored.get(0);

        // 바이트 단위로 같아야 한다. 공백 하나만 사라져도 서명 재검증이 불가능해진다.
        assertThat(new String(event.rawBody(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(event.bodySize()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8).length);
        assertThat(event.status()).isEqualTo(EventStatus.PENDING);
        assertThat(event.attemptCount()).isZero();
        assertThat(event.idempotencyKey()).isEqualTo("d-1");
        assertThat(event.idempotencySource()).isEqualTo(IdempotencySource.PROVIDER_ID);
        // 제공자 서명 헤더는 PASSTHROUGH 전달을 위해 보존된다.
        assertThat(event.rawHeaders()).containsKey(GithubSignatureVerifier.SIGNATURE_HEADER);
    }

    @Test
    @DisplayName("서명이 틀리면 401 이고 원장에 아무것도 남지 않는다")
    void rejectsBadSignature() throws Exception {
        long endpointId = registerGithubEndpoint();

        mockMvc().perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.DELIVERY_HEADER, "d-1")
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign("다른 바디"))
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("invalid_signature"));

        assertThat(events.search(endpointId, null, 10, 0)).isEmpty();
    }

    @Test
    @DisplayName("같은 이벤트 ID 가 다시 오면 200 duplicate 이고 행은 하나뿐이다")
    void blocksDuplicate() throws Exception {
        long endpointId = registerGithubEndpoint();
        var mockMvc = mockMvc();

        for (int i = 0; i < 3; i++) {
            var result = mockMvc.perform(post("/webhooks/gh-push")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(GithubSignatureVerifier.DELIVERY_HEADER, "same-id")
                            .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign(BODY))
                            .content(BODY))
                    .andExpect(status().isOk())
                    .andReturn();
            // 첫 건만 accepted, 나머지는 duplicate. 200 을 주는 이유는 제공자의 재시도를 멈추기 위해서다.
            assertThat(result.getResponse().getContentAsString())
                    .contains(i == 0 ? "accepted" : "duplicate");
        }

        assertThat(events.search(endpointId, null, 10, 0)).hasSize(1);
    }

    @Test
    @DisplayName("동시에 같은 이벤트가 쏟아져도 정확히 한 건만 저장된다")
    void blocksConcurrentDuplicates() throws Exception {
        long endpointId = registerGithubEndpoint();
        var mockMvc = mockMvc();

        int threads = 16;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var accepted = new AtomicInteger();
        var duplicates = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var body = mockMvc.perform(post("/webhooks/gh-push")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header(GithubSignatureVerifier.DELIVERY_HEADER, "race-id")
                                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign(BODY))
                                        .content(BODY))
                                .andReturn().getResponse().getContentAsString();
                        if (body.contains("accepted")) {
                            accepted.incrementAndGet();
                        } else if (body.contains("duplicate")) {
                            duplicates.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        // 조회 후 삽입이었다면 여기서 둘 이상이 통과한다. 유니크 인덱스만이 이걸 막는다(설계 7.4).
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        assertThat(events.search(endpointId, null, 100, 0)).hasSize(1);
    }

    @Test
    @DisplayName("이벤트 ID 를 안 주는 제공자는 바디 해시로 대체하고 그 사실을 원장에 남긴다")
    void fallsBackToBodyHash() throws Exception {
        long endpointId = registerGithubEndpoint();

        mockMvc().perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign(BODY))
                        .content(BODY))
                .andExpect(status().isOk());

        var event = events.search(endpointId, null, 10, 0).get(0);
        assertThat(event.idempotencySource()).isEqualTo(IdempotencySource.BODY_HASH);
        assertThat(event.idempotencyKey())
                .isEqualTo(Hmac.sha256Hex(BODY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("없는 엔드포인트는 404")
    void unknownEndpoint() throws Exception {
        mockMvc().perform(post("/webhooks/nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("꺼진 엔드포인트도 404")
    void disabledEndpoint() throws Exception {
        long id = registerGithubEndpoint();
        endpoints.setEnabled(id, false);

        mockMvc().perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, sign(BODY))
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("시크릿 교체 후에도 구 시크릿으로 서명된 재시도가 유예 기간에는 통과한다")
    void secretRotationGrace() throws Exception {
        registerGithubEndpoint();
        var mockMvc = mockMvc();

        String oldSignature = sign(BODY);
        endpoints.rotateSecret(endpoints.findBySlug("gh-push").orElseThrow().id(),
                "brand-new-secret-9999".getBytes(StandardCharsets.UTF_8));

        // 교체 순간 이미 날아오고 있던 재시도. 유예가 없으면 전부 401 이 된다(설계 6.2).
        mockMvc.perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.DELIVERY_HEADER, "old-sig")
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, oldSignature)
                        .content(BODY))
                .andExpect(status().isOk());

        // 새 시크릿도 물론 통과한다.
        String newSignature = "sha256=" + Hmac.hmacSha256Hex(
                "brand-new-secret-9999".getBytes(StandardCharsets.UTF_8),
                BODY.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/webhooks/gh-push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(GithubSignatureVerifier.DELIVERY_HEADER, "new-sig")
                        .header(GithubSignatureVerifier.SIGNATURE_HEADER, newSignature)
                        .content(BODY))
                .andExpect(status().isOk());
    }
}
