package dev.gateway.webhook.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.gateway.webhook.service.payment.domain.Account;
import dev.gateway.webhook.service.payment.domain.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이 요청이 컨트롤러부터 진짜 MySQL 까지 흐르는 전 구간을 본다.
 * 가짜가 하나도 없다 — 여기서만 배선과 스키마가 검증된다.
 */
class PaymentWebhookIntegrationTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder delivery(String eventId, long orderId, int amount) {
        return post("/webhook/receiver")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Event-Id", eventId)
                .header("X-Gateway-Endpoint", "svc-payment")
                .header("X-Gateway-Attempt", "0")
                .content("{\"orderId\": %d, \"amount\": %d}".formatted(orderId, amount));
    }

    private void given5000BalanceAnd1000Order() {
        accountRepository.save(new Account(1L, 5_000));
        orderRepository.save(new Order(123L, 1L, 1_000));
    }

    private int balanceOf(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("게이트웨이가 보낸 결제 이벤트가 실제 DB 의 잔액을 차감한다")
    void deductsBalanceThroughWholeFlow() throws Exception {
        given5000BalanceAnd1000Order();

        mockMvc.perform(delivery("evt-1", 123L, 1_000))
                .andExpect(status().isOk());

        assertThat(balanceOf(1L)).isEqualTo(4_000);
        assertThat(processedEvents.existsById("evt-1")).isTrue();
    }

    @Test
    @DisplayName("같은 이벤트가 세 번 와도 결제는 한 번만 일어난다")
    void deduplicatesRepeatedDelivery() throws Exception {
        given5000BalanceAnd1000Order();

        // 게이트웨이 전달은 at-least-once 다. 타임아웃 한 번이면 같은 이벤트가 다시 온다.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(delivery("evt-dup", 123L, 1_000))
                    .andExpect(status().isOk());   // 중복도 200 이어야 재시도가 멈춘다
        }

        assertThat(balanceOf(1L)).isEqualTo(4_000);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이벤트 ID 가 다르면 같은 주문이라도 각각 처리된다")
    void differentEventIdsAreProcessedSeparately() throws Exception {
        given5000BalanceAnd1000Order();

        mockMvc.perform(delivery("evt-a", 123L, 1_000)).andExpect(status().isOk());
        mockMvc.perform(delivery("evt-b", 123L, 1_000)).andExpect(status().isOk());

        assertThat(balanceOf(1L)).isEqualTo(3_000);
    }

    @Test
    @DisplayName("없는 주문이면 400 이고 처리 기록도 남지 않는다")
    void unknownOrderLeavesNothingBehind() throws Exception {
        given5000BalanceAnd1000Order();

        mockMvc.perform(delivery("evt-x", 999L, 1_000))
                .andExpect(status().isBadRequest());

        // 실패한 이벤트가 처리됨으로 남으면 재시도가 영영 걸러진다. 롤백을 확인한다.
        assertThat(processedEvents.existsById("evt-x")).isFalse();
        assertThat(balanceOf(1L)).isEqualTo(5_000);
    }

    @Test
    @DisplayName("잔액이 부족하면 422 이고 잔액도 처리 기록도 그대로다")
    void insufficientBalanceRollsBack() throws Exception {
        accountRepository.save(new Account(2L, 500));
        orderRepository.save(new Order(777L, 2L, 1_000));

        mockMvc.perform(delivery("evt-poor", 777L, 1_000))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(2L)).isEqualTo(500);
        assertThat(processedEvents.existsById("evt-poor")).isFalse();
    }
}
