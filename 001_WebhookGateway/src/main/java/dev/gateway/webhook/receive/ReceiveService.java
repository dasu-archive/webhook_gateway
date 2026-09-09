package dev.gateway.webhook.receive;

import dev.gateway.webhook.common.GatewayProperties;
import dev.gateway.webhook.common.HeaderPolicy;
import dev.gateway.webhook.ledger.Endpoint;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.EventRepository;
import dev.gateway.webhook.ledger.IdempotencySource;
import dev.gateway.webhook.receive.signature.EndpointSecrets;
import dev.gateway.webhook.receive.signature.Hmac;
import dev.gateway.webhook.receive.signature.SignatureVerifier;
import dev.gateway.webhook.receive.signature.SignatureVerifiers;
import dev.gateway.webhook.receive.signature.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 수신 경로. 설계 6절.
 *
 * <p>여기서 하는 일은 검증과 저장뿐이다(P1). 페이로드는 열어보지 않는다(P6).
 * 목표 지연 p99 50ms 는 이 경로에 DB 쓰기 한 번만 두어서 달성한다.
 */
@Service
public class ReceiveService {

    private static final Logger log = LoggerFactory.getLogger(ReceiveService.class);

    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final SignatureVerifiers verifiers;
    private final GatewayProperties properties;
    private final Clock clock;

    public ReceiveService(EndpointRepository endpoints, EventRepository events,
                          SignatureVerifiers verifiers, GatewayProperties properties, Clock clock) {
        this.endpoints = endpoints;
        this.events = events;
        this.verifiers = verifiers;
        this.properties = properties;
        this.clock = clock;
    }

    public ReceiveOutcome receive(String slug, byte[] rawBody, HttpHeaders headers) {
        Endpoint endpoint = endpoints.findBySlug(slug).orElse(null);
        if (endpoint == null || !endpoint.enabled()) {
            // 알 수 없는(또는 꺼진) 엔드포인트. 설계 6.4
            return ReceiveOutcome.unknownEndpoint();
        }

        int maxBytes = properties.getReceive().getMaxBodyBytes();
        if (rawBody.length > maxBytes) {
            // 설계 13절 "큰 페이로드". 임계값 이상은 오브젝트 스토리지 분리가 v1 과제다.
            return ReceiveOutcome.tooLarge(rawBody.length, maxBytes);
        }

        SignatureVerifier verifier = verifiers.find(endpoint.provider()).orElse(null);
        if (verifier == null) {
            // 등록 시 막지만, 검증기를 떼어낸 채 배포하면 여기로 온다. 설정 오류지 제공자 잘못이 아니다.
            log.error("엔드포인트 {} 의 provider '{}' 에 대한 검증기가 없다. 지원: {}",
                    slug, endpoint.provider(), verifiers.supported());
            return ReceiveOutcome.misconfigured(endpoint.provider());
        }

        var secrets = new EndpointSecrets(
                endpoint.secretCurrent(),
                endpoint.previousSecretUsable(clock.instant(), properties.getReceive().getSecretRotationGrace())
                        ? endpoint.secretPrevious()
                        : null);

        VerificationResult verification = verifier.verify(rawBody, headers, secrets);
        if (!verification.valid()) {
            // 401. 재시도를 유발하지 않는다 — 서명이 틀린 요청은 다시 와도 틀린다.
            log.warn("서명 검증 실패 slug={} 사유={}", slug, verification.reason());
            return ReceiveOutcome.invalidSignature(verification.reason());
        }

        String eventId = verifier.extractEventId(headers).orElse(null);
        IdempotencySource source = eventId != null ? IdempotencySource.PROVIDER_ID : IdempotencySource.BODY_HASH;
        String idempotencyKey = eventId != null ? eventId : Hmac.sha256Hex(rawBody);

        try {
            long id = events.insert(endpoint.id(), idempotencyKey, source, rawBody,
                    storableHeaders(headers), headers.getFirst(HttpHeaders.CONTENT_TYPE));
            return ReceiveOutcome.accepted(id);
        } catch (DuplicateKeyException e) {
            // 조회 후 삽입은 동시 삽입에서 둘 다 통과한다. 유니크 인덱스로만 막는다. 설계 7.4
            log.debug("중복 수신 slug={} key={}", slug, idempotencyKey);
            return ReceiveOutcome.duplicate(idempotencyKey);
        }
    }

    /**
     * 다중 값 헤더는 첫 값만 남긴다. 서명 헤더가 여러 개인 제공자는 아직 없고,
     * 원장이 커지는 것을 막는 편을 택했다. 필요해지면 JSON 컬럼 구조를 배열로 바꾼다.
     */
    private Map<String, String> storableHeaders(HttpHeaders headers) {
        var out = new LinkedHashMap<String, String>();
        headers.forEach((name, values) -> {
            if (HeaderPolicy.storable(name) && !values.isEmpty()) {
                out.put(name, values.get(0));
            }
        });
        return out;
    }
}
