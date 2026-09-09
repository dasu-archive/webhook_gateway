package dev.gateway.webhook.service.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Order 애그리거트의 저장소. {@link AccountRepository} 와 같은 이유로 Spring Data 를 그대로 쓴다.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
