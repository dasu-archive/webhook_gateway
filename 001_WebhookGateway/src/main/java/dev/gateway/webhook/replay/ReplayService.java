package dev.gateway.webhook.replay;

import dev.gateway.webhook.ledger.Endpoint;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.Replay;
import dev.gateway.webhook.ledger.ReplayRepository;
import dev.gateway.webhook.ledger.ReplayVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 재생. README 3.6.
 *
 * <p>어드민은 소비자에게 아무것도 보내지 않는다. DEAD 를 PENDING 으로 되돌릴 뿐이고,
 * 실제 전송은 워커가 원래 하던 대로 한다. 값나가는 건 그 UPDATE 한 줄 앞의 가드 네 개다.
 * <pre>
 * 1. 4xx 로 죽은 건 제외      건 단위 — UPDATE 조건
 * 2. 이미 재생된 건 제외      건 단위 — UPDATE 조건
 * 3. 소비자가 응답해야 한다   엔드포인트 단위 — 실행 전
 * 4. 멱등 처리 선언이 있어야  엔드포인트 단위 — 실행 전
 * </pre>
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    static final int SAMPLE_SIZE = 20;

    private final EndpointRepository endpoints;
    private final ReplayRepository replays;
    private final ConsumerProbe probe;
    private final TransactionTemplate tx;

    public ReplayService(EndpointRepository endpoints, ReplayRepository replays, ConsumerProbe probe,
                         PlatformTransactionManager transactionManager) {
        this.endpoints = endpoints;
        this.replays = replays;
        this.probe = probe;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 아무것도 바꾸지 않는다. 대상 건수 · 제외 사유 · 목적지 · 가드 상태만 계산한다. */
    public ReplayDto.DryRunView dryRun(String slug, Instant from, Instant to) {
        Endpoint endpoint = requireEndpoint(slug);
        requireValidRange(from, to);

        Map<ReplayVerdict, Long> verdicts = replays.classify(endpoint.id(), from, to);
        long eligible = verdicts.get(ReplayVerdict.ELIGIBLE);
        long matched = verdicts.values().stream().mapToLong(Long::longValue).sum();
        var skipped = new LinkedHashMap<String, Long>();
        skipped.put(ReplayVerdict.PERMANENT_4XX.name(), verdicts.get(ReplayVerdict.PERMANENT_4XX));
        skipped.put(ReplayVerdict.ALREADY_REPLAYED.name(), verdicts.get(ReplayVerdict.ALREADY_REPLAYED));

        ConsumerProbe.Result health = probe.probe(endpoint.targetUrl());
        var guards = new ReplayDto.Guards(endpoint.idempotencyConfirmed(), health.healthy(), health.describe());

        return new ReplayDto.DryRunView(endpoint.slug(), endpoint.targetUrl(), "DEAD", from, to,
                matched, eligible, skipped,
                replays.sampleEligible(endpoint.id(), from, to, SAMPLE_SIZE),
                guards,
                eligible > 0 && endpoint.idempotencyConfirmed() && health.healthy());
    }

    /**
     * 헬스체크는 네트워크 호출이라 트랜잭션 밖에서 먼저 한다 — 커넥션을 쥔 채 소비자를 기다리지 않는다.
     * 이력 기록과 상태 전이는 한 트랜잭션이다. 건수가 어긋나면 둘 다 없던 일이 된다.
     */
    public Replay execute(ReplayDto.ExecuteRequest request) {
        Endpoint endpoint = requireEndpoint(request.endpoint());
        Instant from = request.receivedFrom();
        Instant to = request.receivedTo();
        requireValidRange(from, to);

        if (!endpoint.idempotencyConfirmed()) {
            throw new ReplayRejectedException(ReplayRejectedException.Reason.IDEMPOTENCY_NOT_CONFIRMED,
                    "엔드포인트 " + endpoint.slug() + " 는 멱등 처리 선언이 없다. "
                            + "선언 없이 다시 보내면 소비자의 부작용이 다시 일어난다");
        }
        ConsumerProbe.Result health = probe.probe(endpoint.targetUrl());
        if (!health.healthy()) {
            throw new ReplayRejectedException(ReplayRejectedException.Reason.CONSUMER_UNHEALTHY,
                    "소비자가 응답하지 않는다: " + health.describe());
        }

        int expected = request.expectedCount();
        Replay replay = tx.execute(status -> {
            long replayId = replays.insert(endpoint.id(), from, to, request.requestedBy(), request.reason(), expected);
            int moved = replays.markForReplay(replayId, endpoint.id(), from, to);
            if (moved != expected) {
                // 드라이런 이후 범위 안의 DEAD 가 늘거나 줄었다. 사람이 본 숫자와 다르면 실행하지 않는다.
                throw new ReplayRejectedException(ReplayRejectedException.Reason.COUNT_MISMATCH,
                        "드라이런 기준 " + expected + "건인데 지금은 " + moved + "건이다. 드라이런을 다시 하라");
            }
            return replays.findById(replayId).orElseThrow();
        });
        log.info("재생 {} endpoint={} {}건 요청자={} 사유={}",
                replay.id(), endpoint.slug(), replay.eventCount(), replay.requestedBy(), replay.reason());
        return replay;
    }

    private Endpoint requireEndpoint(String slug) {
        return endpoints.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 엔드포인트: " + slug));
    }

    private static void requireValidRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receivedFrom 은 receivedTo 보다 앞이어야 한다");
        }
    }
}
