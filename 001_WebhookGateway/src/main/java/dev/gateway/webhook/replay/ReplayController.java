package dev.gateway.webhook.replay;

import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.ReplayRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 재생 API. /admin 아래라 어드민 토큰 필터가 그대로 걸린다.
 *
 * <pre>
 * POST /admin/replays/dry-run   대상 계산만. 아무것도 바꾸지 않는다
 * POST /admin/replays           실행. 드라이런의 eligible 을 expectedCount 로 넘긴다
 * GET  /admin/replays           이력
 * GET  /admin/replays/{id}      이력 + 옮긴 이벤트의 현재 상태
 * </pre>
 */
@RestController
@RequestMapping("/admin/replays")
public class ReplayController {

    private final ReplayService service;
    private final ReplayRepository replays;
    private final EndpointRepository endpoints;

    public ReplayController(ReplayService service, ReplayRepository replays, EndpointRepository endpoints) {
        this.service = service;
        this.replays = replays;
        this.endpoints = endpoints;
    }

    @PostMapping("/dry-run")
    public ReplayDto.DryRunView dryRun(@Valid @RequestBody ReplayDto.ScopeRequest request) {
        return service.dryRun(request.endpoint(), request.receivedFrom(), request.receivedTo());
    }

    @PostMapping
    public ResponseEntity<ReplayDto.ReplayView> execute(@Valid @RequestBody ReplayDto.ExecuteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ReplayDto.ReplayView.of(service.execute(request)));
    }

    @GetMapping
    public List<ReplayDto.ReplayView> list(@RequestParam(required = false) String endpoint,
                                           @RequestParam(defaultValue = "50") int limit) {
        Long endpointId = endpoint == null ? null : endpoints.findBySlug(endpoint)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 엔드포인트: " + endpoint))
                .id();
        return replays.findRecent(endpointId, Math.clamp(limit, 1, 500)).stream()
                .map(ReplayDto.ReplayView::of)
                .toList();
    }

    @GetMapping("/{id}")
    public ReplayDto.ReplayDetailView get(@PathVariable long id) {
        var replay = replays.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 재생: " + id));
        return new ReplayDto.ReplayDetailView(ReplayDto.ReplayView.of(replay), replays.countEventsByStatus(id));
    }

    /** 가드에 걸리면 409. 요청 형식은 맞지만 지금 상태로는 할 수 없다는 뜻이다. */
    @ExceptionHandler(ReplayRejectedException.class)
    public ResponseEntity<ProblemDetail> rejected(ReplayRejectedException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("replay rejected");
        problem.setProperty("reason", e.reason().name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
