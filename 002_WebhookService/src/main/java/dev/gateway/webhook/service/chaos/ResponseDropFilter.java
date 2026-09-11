package dev.gateway.webhook.service.chaos;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 웹훅 수신의 2xx 응답을 확률적으로 끊는다.
 *
 * <p><b>처리가 다 끝난 뒤에 끊어야 한다.</b> 결제와 처리 기록은 한 트랜잭션이라, 그 안에서 끊으면
 * 롤백되어 "처리 실패"를 흉내 내게 된다. 그건 5xx 재시도와 같은 시나리오다. 그래서 컨트롤러가 아니라
 * 필터에서, 체인이 돌아온 뒤 — 트랜잭션이 커밋된 뒤 — 에 결정한다.
 *
 * <p>끊는 방법: 응답 본문을 붙잡아 두었다가 Content-Length 를 실제보다 1바이트 크게 약속하고
 * {@code Connection: close} 로 닫는다. 게이트웨이는 헤더까지 받은 뒤 본문이 모자란 채 연결이 닫히는 걸 보고
 * IOException 을 던진다. 게이트웨이 쪽 분류로는 CONN_RESET 이다.
 */
@Component
@ConditionalOnProperty(prefix = "webhook.chaos", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ResponseDropFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ResponseDropFilter.class);

    private final ResponseDropPolicy policy;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var captured = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, captured);

        int status = captured.getStatus();
        if (status >= 200 && status < 300 && policy.shouldDrop()) {
            byte[] body = captured.getContentAsByteArray();
            response.setHeader(HttpHeaders.CONNECTION, "close");
            response.setContentLength(body.length + 1);
            response.getOutputStream().write(body);
            response.flushBuffer();
            policy.recordDrop();
            log.warn("응답 드롭 eventId={} — 처리는 끝났고, 게이트웨이는 실패로 보고 다시 보낸다",
                    request.getHeader("X-Gateway-Event-Id"));
            return;
        }
        captured.copyBodyToResponse();
    }
}
