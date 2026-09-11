package dev.gateway.webhook.replay;

import dev.gateway.webhook.dispatch.FailureClassifier;
import dev.gateway.webhook.ledger.FailureClass;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 재생 가드 3번. 소비자가 지금 응답하는가.
 *
 * <p>헬스 URL 을 따로 등록받지 않고 target_url 에 HEAD 를 보낸다. 보는 건 "응답이 오는가"뿐이다 —
 * POST 전용 핸들러는 405 를 주는데, 그것도 프로세스가 살아 있다는 뜻이라 통과다.
 * 5xx 와 연결 실패만 막는다. HEAD 를 쓰는 건 부작용이 없는 메서드라서다.
 *
 * <p>한계: 응답한다고 처리할 수 있다는 뜻은 아니다. 재생 뒤의 실패는 원래대로 재시도가 받는다.
 */
@Component
public class ConsumerProbe {

    /** 어드민 요청 안에서 기다리는 시간이다. 전달 타임아웃(10s)보다 짧게 둔다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient httpClient;

    public ConsumerProbe(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Result probe(String targetUrl) {
        var request = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status < 500
                    ? new Result(true, status, null)
                    : new Result(false, status, FailureClass.HTTP_5XX);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, FailureClass.OTHER);
        } catch (Exception e) {
            return new Result(false, null, FailureClassifier.ofException(e));
        }
    }

    public record Result(boolean healthy, Integer status, FailureClass failureClass) {

        /** 사람이 읽을 한 줄. "HTTP 405" 또는 "CONN_REFUSED" */
        public String describe() {
            return status != null ? "HTTP " + status : String.valueOf(failureClass);
        }
    }
}
