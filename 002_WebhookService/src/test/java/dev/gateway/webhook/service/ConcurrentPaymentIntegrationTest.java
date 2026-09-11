package dev.gateway.webhook.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import dev.gateway.webhook.service.payment.domain.Account;
import dev.gateway.webhook.service.payment.domain.Order;
import dev.gateway.webhook.service.webhook.PaymentEvent;
import dev.gateway.webhook.service.webhook.WebhookReceiver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 결제. 게이트웨이 워커는 여러 개라 같은 계좌로 가는 결제가 동시에 온다.
 *
 * <p>다른 테스트는 전부 한 건씩 순서대로 보내서 동시성 결함을 볼 수 없었다. 응답 드롭 시나리오를
 * compose 에서 돌렸을 때 처리 기록 100건에 결제 20~25회분만 차감된 것으로 드러났다(lost update).
 */
class ConcurrentPaymentIntegrationTest extends IntegrationTestBase {

    /** 커넥션 풀(20)보다 작게 둔다. 락을 기다리는 스레드도 커넥션을 쥐고 있다. */
    private static final int THREADS = 16;

    @Autowired
    WebhookReceiver webhookReceiver;

    private void given100000BalanceAnd1000Order() {
        accountRepository.save(new Account(1L, 100_000));
        orderRepository.save(new Order(123L, 1L, 1_000));
    }

    private int balanceOf(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("같은 계좌로 서로 다른 결제가 동시에 와도 전부 차감된다 — 읽고 빼고 저장하는 사이에 덮어쓰면 안 된다")
    void concurrentPaymentsAreAllDeducted() throws Exception {
        given100000BalanceAnd1000Order();

        var errors = runConcurrently(THREADS,
                i -> webhookReceiver.receivePaymentDelivery("evt-" + i, new PaymentEvent(123L, 1_000)));

        assertThat(errors).isEmpty();
        assertThat(processedEvents.count()).isEqualTo(THREADS);
        assertThat(balanceOf(1L)).isEqualTo(100_000 - THREADS * 1_000);
    }

    @Test
    @DisplayName("같은 이벤트가 동시에 와도 결제는 한 번이다 — 진 쪽은 처리 기록 PK 에 막혀 롤백되고 게이트웨이 재시도로 넘어간다")
    void concurrentDuplicatesChargeOnce() throws Exception {
        given100000BalanceAnd1000Order();

        var errors = runConcurrently(10,
                i -> webhookReceiver.receivePaymentDelivery("evt-same", new PaymentEvent(123L, 1_000)));

        assertThat(balanceOf(1L)).isEqualTo(99_000);
        assertThat(processedEvents.count()).isEqualTo(1);
        // 늦게 온 쪽은 조회에서 걸러져 조용히 끝나거나(200), PK 충돌로 롤백된다(500 → 게이트웨이가 다시 보낸다).
        assertThat(errors).allSatisfy(e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class));
    }

    /** n 개 스레드를 한 출발선에 세웠다가 동시에 놓는다. */
    private static List<Throwable> runConcurrently(int n, IntConsumer task) throws InterruptedException {
        var pool = Executors.newFixedThreadPool(n);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var errors = new CopyOnWriteArrayList<Throwable>();
        for (int i = 0; i < n; i++) {
            int index = i;
            pool.execute(() -> {
                try {
                    start.await();
                    task.accept(index);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        return errors;
    }
}
