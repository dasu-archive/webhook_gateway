package dev.gateway.webhook.service.payment.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 결제 대금이 빠져나가는 계좌. 잔액 변경 규칙은 전부 이 애그리거트 안에서만 일어난다.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private Long id;

    @Column(nullable = false)
    private int balance;

    /** JPA 전용. 애플리케이션 코드에서 쓰지 않는다. */
    protected Account() {
    }

    public Account(Long id, int balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("잔액은 음수일 수 없습니다: " + balance);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.balance = balance;
    }

    /**
     * 결제 금액만큼 잔액을 차감한다.
     */
    public void withdraw(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("출금액은 0보다 커야 합니다: " + amount);
        }
        if (balance < amount) {
            throw new InsufficientBalanceException(id, balance, amount);
        }
        balance -= amount;
    }

    public Long getId() {
        return id;
    }

    public int getBalance() {
        return balance;
    }
}
