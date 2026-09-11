package dev.gateway.webhook.service.payment.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.gateway.webhook.service.payment.domain.Account;
import dev.gateway.webhook.service.payment.domain.AccountNotFoundException;
import dev.gateway.webhook.service.payment.domain.AccountRepository;
import dev.gateway.webhook.service.payment.domain.Order;
import dev.gateway.webhook.service.payment.domain.OrderNotFoundException;
import dev.gateway.webhook.service.payment.domain.OrderRepository;

/**
 * 결제 유스케이스. 애그리거트를 조회해 도메인 규칙을 실행시키고 결과를 저장하는 역할만 한다.
 */
@Service
public class PaymentService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;

    public PaymentService(OrderRepository orderRepository, AccountRepository accountRepository) {
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * 주문 금액만큼 주문자의 계좌에서 대금을 차감한다.
     *
     * <p>계좌는 잠그고 읽는다({@link AccountRepository#findForUpdate}). 잠그지 않으면 같은 계좌로 동시에 온
     * 결제들이 차감을 서로 덮어써, 처리 기록은 남는데 돈은 덜 빠진다.
     */
    @Transactional
    public void processPayment(long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        Account account = accountRepository.findForUpdate(order.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(order.getAccountId()));

        account.withdraw(order.getAmount());
        accountRepository.save(account);
    }
}
