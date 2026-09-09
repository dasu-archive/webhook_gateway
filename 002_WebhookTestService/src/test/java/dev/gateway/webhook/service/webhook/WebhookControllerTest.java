package dev.gateway.webhook.service.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.gateway.webhook.service.payment.domain.InsufficientBalanceException;
import dev.gateway.webhook.service.payment.domain.OrderNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 계약만 본다. 여기서 확인하는 상태 코드가 곧 게이트웨이에 대한 지시다 —
 * 4xx 면 이벤트를 버리고, 5xx 면 재시도한다.
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WebhookReceiver webhookReceiver;

    /** 게이트웨이가 실제로 보내는 모양의 요청. */
    private static MockHttpServletRequestBuilder delivery(String body) {
        return post("/webhook/receiver")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Event-Id", "evt-1")
                .header("X-Gateway-Endpoint", "svc-payment")
                .header("X-Gateway-Attempt", "0")
                .content(body);
    }

    @Test
    @DisplayName("게이트웨이 형식 요청을 받으면 200 이고 이벤트 ID 와 함께 리시버로 넘긴다")
    void acceptsGatewayDelivery() throws Exception {
        mockMvc.perform(delivery("{\"orderId\": 123, \"amount\": 1000}"))
                .andExpect(status().isOk());

        verify(webhookReceiver).receivePaymentDelivery("evt-1", new PaymentEvent(123L, 1_000));
    }

    @Test
    @DisplayName("페이로드의 orderId 가 그대로 전달된다")
    void passesOrderIdFromPayload() throws Exception {
        mockMvc.perform(delivery("{\"orderId\": 456, \"amount\": 2000}"))
                .andExpect(status().isOk());

        verify(webhookReceiver).receivePaymentDelivery("evt-1", new PaymentEvent(456L, 2_000));
    }

    @Test
    @DisplayName("orderId 가 없으면 400 이고 결제로 넘어가지 않는다")
    void rejectsMissingOrderId() throws Exception {
        mockMvc.perform(delivery("{\"amount\": 1000}"))
                .andExpect(status().isBadRequest());

        verify(webhookReceiver, never()).receivePaymentDelivery(anyString(), any());
    }

    @Test
    @DisplayName("이벤트 ID 헤더가 없으면 400 — 중복 제거를 할 수 없는 요청은 받지 않는다")
    void rejectsMissingEventIdHeader() throws Exception {
        mockMvc.perform(post("/webhook/receiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": 123, \"amount\": 1000}"))
                .andExpect(status().isBadRequest());

        verify(webhookReceiver, never()).receivePaymentDelivery(anyString(), any());
    }

    @Test
    @DisplayName("없는 주문이면 400 — 재시도해도 없으므로 게이트웨이가 즉시 포기하게 한다")
    void mapsOrderNotFoundTo400() throws Exception {
        doThrow(new OrderNotFoundException(123L))
                .when(webhookReceiver).receivePaymentDelivery(anyString(), any());

        mockMvc.perform(delivery("{\"orderId\": 123, \"amount\": 1000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잔액이 부족하면 422 — 결제 실패지 시스템 오류가 아니다")
    void mapsInsufficientBalanceTo422() throws Exception {
        doThrow(new InsufficientBalanceException(1L, 500, 1_000))
                .when(webhookReceiver).receivePaymentDelivery(anyString(), any());

        mockMvc.perform(delivery("{\"orderId\": 123, \"amount\": 1000}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
