package dev.gateway.webhook.service.payment.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 결제 대상 주문. 생성된 뒤에는 값이 바뀌지 않는다.
 *
 * <p>order 는 MySQL 예약어라 테이블명은 orders 를 쓴다.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private int amount;

    /** JPA 전용. 애플리케이션 코드에서 쓰지 않는다. */
    protected Order() {
    }

    public Order(Long id, Long accountId, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("주문 금액은 0보다 커야 합니다: " + amount);
        }
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public int getAmount() {
        return amount;
    }
}
