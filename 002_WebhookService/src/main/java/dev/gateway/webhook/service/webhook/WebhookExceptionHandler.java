package dev.gateway.webhook.service.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.gateway.webhook.service.payment.domain.AccountNotFoundException;
import dev.gateway.webhook.service.payment.domain.InsufficientBalanceException;
import dev.gateway.webhook.service.payment.domain.OrderNotFoundException;

/**
 * 예외를 게이트웨이가 알아들을 응답 코드로 바꾼다.
 *
 * <p>기준은 하나다 — "똑같은 요청을 다시 보내면 성공할 가능성이 있는가."
 * 있으면 5xx 로 재시도를 받고, 없으면 4xx 로 즉시 포기시킨다. 이 판단을 틀리면
 * 영원히 실패할 요청을 계속 받거나(4xx 를 5xx 로), 살릴 수 있는 이벤트를 버린다(5xx 를 4xx 로).
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

    /**
     * 없는 주문·계좌는 다시 보내도 없다. 400 으로 게이트웨이가 즉시 데드레터로 보내게 한다.
     */
    @ExceptionHandler({OrderNotFoundException.class, AccountNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException e) {
        log.warn("처리할 수 없는 이벤트: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 잔액 부족은 결제 실패지 시스템 오류가 아니다. 422 로 재시도를 막는다.
     *
     * <p>충전을 기대해 5xx 로 재시도를 받는 선택도 가능하다. 다만 게이트웨이의 백오프는
     * 최대 1시간이라 충전을 기다리기엔 짧고, 그동안 워커를 붙잡는다. 결제 실패는
     * 인프라 재시도가 아니라 사용자에게 알려 해결할 일이라고 보고 4xx 를 택했다.
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientBalanceException e) {
        log.warn("잔액 부족으로 결제 실패: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
