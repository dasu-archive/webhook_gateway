package dev.gateway.webhook.service.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잔액 규칙만 본다. Spring 도 Mockito 도 필요 없는 가장 빠른 테스트다.
 */
class AccountTest {

    @Test
    @DisplayName("출금하면 잔액이 그만큼 줄어든다")
    void withdrawDeductsBalance() {
        Account account = new Account(1L, 5_000);

        account.withdraw(1_000);

        assertThat(account.getBalance()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("잔액과 출금액이 정확히 같으면 0원이 되고 성공한다")
    void withdrawExactBalance() {
        Account account = new Account(1L, 1_000);

        account.withdraw(1_000);

        assertThat(account.getBalance()).isZero();
    }

    @Test
    @DisplayName("잔액보다 1원이라도 많이 출금하면 실패하고 잔액은 그대로다")
    void withdrawMoreThanBalance() {
        Account account = new Account(1L, 1_000);

        assertThatThrownBy(() -> account.withdraw(1_001))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("잔액이 부족합니다");

        // 실패했으면 아무것도 바뀌지 않아야 한다
        assertThat(account.getBalance()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("출금액이 0 이하면 예외")
    void withdrawNonPositiveAmount() {
        Account account = new Account(1L, 5_000);

        assertThatThrownBy(() -> account.withdraw(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.withdraw(-100))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(account.getBalance()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("잔액이 음수인 계좌는 만들 수 없다")
    void cannotCreateWithNegativeBalance() {
        assertThatThrownBy(() -> new Account(1L, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
