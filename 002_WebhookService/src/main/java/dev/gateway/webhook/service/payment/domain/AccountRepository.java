package dev.gateway.webhook.service.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Account 애그리거트의 저장소.
 *
 * <p>테스트용 서비스라 포트/어댑터를 따로 두지 않고 Spring Data 인터페이스를 그대로 쓴다.
 * 도메인이 JPA 를 알게 되는 대신 클래스 수가 절반이 된다.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {
}
