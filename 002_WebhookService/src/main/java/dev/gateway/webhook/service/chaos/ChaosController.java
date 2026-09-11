package dev.gateway.webhook.service.chaos;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * 카오스 제어. {@code webhook.chaos.enabled=true} 일 때만 뜬다.
 *
 * <pre>
 * GET  /chaos/response-drop            현재 드롭률과 드롭 횟수
 * POST /chaos/response-drop?rate=0.3   드롭률 변경. 드롭 횟수는 0 으로 되돌린다
 * </pre>
 *
 * <p>재기동 없이 바꾸는 이유는 재기동 자체가 또 하나의 장애라서다. compose 에서는 소비자 포트를
 * 호스트에 열지 않으므로 컨테이너 안에서 부른다.
 * {@code docker exec wg-service curl -s -X POST 'localhost:8081/chaos/response-drop?rate=0.3'}
 */
@RestController
@RequestMapping("/chaos/response-drop")
@ConditionalOnProperty(prefix = "webhook.chaos", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChaosController {

    private final ResponseDropPolicy policy;

    @GetMapping
    public Map<String, Object> state() {
        return Map.of("rate", policy.rate(), "dropped", policy.dropped());
    }

    @PostMapping
    public Map<String, Object> change(@RequestParam double rate) {
        if (rate < 0 || rate > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "드롭률은 0 이상 1 이하여야 한다: " + rate);
        }
        policy.setRate(rate);
        return state();
    }
}
