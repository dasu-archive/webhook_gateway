package dev.gateway.webhook.service.payment.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gateway.webhook.service.payment.domain.Account;
import dev.gateway.webhook.service.payment.domain.AccountNotFoundException;
import dev.gateway.webhook.service.payment.domain.AccountRepository;
import dev.gateway.webhook.service.payment.domain.InsufficientBalanceException;
import dev.gateway.webhook.service.payment.domain.Order;
import dev.gateway.webhook.service.payment.domain.OrderNotFoundException;
import dev.gateway.webhook.service.payment.domain.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 결제 유스케이스의 조립을 본다. 저장소는 가짜고 도메인은 진짜다.
 */
class PaymentServiceTest {

    private OrderRepository orderRepository;
    private AccountRepository accountRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        accountRepository = mock(AccountRepository.class);
        paymentService = new PaymentService(orderRepository, accountRepository);
    }

    @Test
    @DisplayName("결제하면 주문자의 계좌에서 주문 금액만큼 빠진다")
    void deductsOrderAmountFromAccount() {
        // given
        Account account = new Account(1L, 5_000);
        when(orderRepository.findById(123L)).thenReturn(Optional.of(new Order(123L, 1L, 1_000)));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        // when
        paymentService.processPayment(123L);

        // then
        assertThat(account.getBalance()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("페이로드 금액이 아니라 주문에 저장된 금액으로 차감한다")
    void usesStoredOrderAmount() {
        // 외부에서 온 금액을 믿지 않는다. 게이트웨이가 무슨 금액을 실어 보내든
        // 실제 차감은 우리 원장의 주문 금액을 따른다.
        Account account = new Account(1L, 5_000);
        when(orderRepository.findById(123L)).thenReturn(Optional.of(new Order(123L, 1L, 2_500)));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        paymentService.processPayment(123L);

        assertThat(account.getBalance()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("없는 주문이면 OrderNotFoundException")
    void throwsWhenOrderMissing() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(999L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("주문은 있는데 계좌가 없으면 AccountNotFoundException")
    void throwsWhenAccountMissing() {
        when(orderRepository.findById(123L)).thenReturn(Optional.of(new Order(123L, 7L, 1_000)));
        when(accountRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(123L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("잔액이 부족하면 InsufficientBalanceException 이 그대로 올라온다")
    void propagatesInsufficientBalance() {
        Account account = new Account(1L, 500);
        when(orderRepository.findById(123L)).thenReturn(Optional.of(new Order(123L, 1L, 1_000)));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> paymentService.processPayment(123L))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(account.getBalance()).isEqualTo(500);
    }
}
