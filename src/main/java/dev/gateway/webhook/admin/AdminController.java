package dev.gateway.webhook.admin;

import dev.gateway.webhook.ledger.Endpoint;
import dev.gateway.webhook.ledger.EndpointRepository;
import dev.gateway.webhook.ledger.EventRepository;
import dev.gateway.webhook.ledger.EventStatus;
import dev.gateway.webhook.ledger.SignatureMode;
import dev.gateway.webhook.receive.signature.SignatureVerifiers;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 관리 평면. 설계 5절 — 수신/전달 흐름 바깥에 있다.
 *
 * <p>MVP 범위는 엔드포인트 등록·시크릿 로테이션·원장 조회까지다.
 * 재생(설계 9절)은 v1 에서 이 컨트롤러에 붙는다.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final SignatureVerifiers verifiers;

    public AdminController(EndpointRepository endpoints, EventRepository events, SignatureVerifiers verifiers) {
        this.endpoints = endpoints;
        this.events = events;
        this.verifiers = verifiers;
    }

    @PostMapping("/endpoints")
    public ResponseEntity<AdminDto.EndpointView> create(@Valid @RequestBody AdminDto.CreateEndpointRequest request) {
        if (verifiers.find(request.provider()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "지원하지 않는 provider: " + request.provider() + ". 지원: " + verifiers.supported());
        }
        try {
            long id = endpoints.insert(
                    request.slug(),
                    request.provider().toLowerCase(java.util.Locale.ROOT),
                    request.secret().getBytes(StandardCharsets.UTF_8),
                    request.targetUrl(),
                    SignatureMode.PASSTHROUGH,
                    request.maxAttemptsOrDefault(),
                    request.retentionDaysOrDefault());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminDto.EndpointView.of(endpoints.findById(id).orElseThrow()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 있는 slug: " + request.slug());
        }
    }

    @GetMapping("/endpoints")
    public List<AdminDto.EndpointView> list() {
        return endpoints.findAll().stream().map(AdminDto.EndpointView::of).toList();
    }

    @GetMapping("/endpoints/{slug}")
    public AdminDto.EndpointView get(@PathVariable String slug) {
        return AdminDto.EndpointView.of(require(slug));
    }

    /**
     * 시크릿 교체. 구 시크릿은 secret_previous 로 밀려나 유예 기간 동안 계속 통과한다.
     * 유예 없이 갈아끼우면 구 시크릿으로 서명된 재시도가 전부 401 이 된다(설계 6.2).
     */
    @PostMapping("/endpoints/{slug}/secret")
    public AdminDto.EndpointView rotateSecret(@PathVariable String slug,
                                              @Valid @RequestBody AdminDto.RotateSecretRequest request) {
        Endpoint endpoint = require(slug);
        endpoints.rotateSecret(endpoint.id(), request.secret().getBytes(StandardCharsets.UTF_8));
        return AdminDto.EndpointView.of(endpoints.findById(endpoint.id()).orElseThrow());
    }

    @PostMapping("/endpoints/{slug}/enabled")
    public AdminDto.EndpointView setEnabled(@PathVariable String slug, @RequestParam boolean value) {
        Endpoint endpoint = require(slug);
        endpoints.setEnabled(endpoint.id(), value);
        return AdminDto.EndpointView.of(endpoints.findById(endpoint.id()).orElseThrow());
    }

    @GetMapping("/events")
    public List<AdminDto.EventView> searchEvents(@RequestParam(required = false) String endpoint,
                                                 @RequestParam(required = false) EventStatus status,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(defaultValue = "0") long afterId) {
        Long endpointId = endpoint == null ? null : require(endpoint).id();
        return events.search(endpointId, status, Math.clamp(limit, 1, 500), afterId).stream()
                .map(AdminDto.EventView::of)
                .toList();
    }

    @GetMapping("/events/{id}")
    public AdminDto.EventDetailView getEvent(@PathVariable long id) {
        var event = events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 이벤트: " + id));
        return AdminDto.EventDetailView.of(event, events.findAttempts(id));
    }

    /** 데드레터가 쌓이는지 눈으로 확인하는 최소 창구. 설계 8.5. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "eventsByStatus", events.countByStatus(),
                "endpoints", endpoints.findAll().size(),
                "supportedProviders", verifiers.supported());
    }

    private Endpoint require(String slug) {
        return endpoints.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 엔드포인트: " + slug));
    }
}
