package dev.gateway.webhook.service.payment.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Account 애그리거트의 저장소.
 *
 * <p>테스트용 서비스라 포트/어댑터를 따로 두지 않고 Spring Data 인터페이스를 그대로 쓴다.
 * 도메인이 JPA 를 알게 되는 대신 클래스 수가 절반이 된다.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * 결제용 조회. {@code SELECT ... FOR UPDATE} 로 같은 계좌의 결제를 줄 세운다.
     *
     * <p>잠금 없이 읽고 빼고 저장하면 동시에 온 결제들이 같은 옛 잔액을 읽어 서로 덮어쓴다(lost update).
     * 게이트웨이 워커가 여러 개라 같은 계좌로 가는 결제는 실제로 동시에 온다.
     *
     * <p>낙관적 잠금(@Version)은 쓰지 않았다. 충돌마다 500 이 나가 게이트웨이 재시도가 되고,
     * 그 재시도가 결과표의 "중복 전달"에 섞여 응답 드롭이 만든 숫자와 구별되지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findForUpdate(@Param("id") Long id);
}
