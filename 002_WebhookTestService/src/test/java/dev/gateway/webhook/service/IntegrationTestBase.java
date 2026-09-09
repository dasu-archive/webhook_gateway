package dev.gateway.webhook.service;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import dev.gateway.webhook.service.payment.domain.AccountRepository;
import dev.gateway.webhook.service.payment.domain.OrderRepository;
import dev.gateway.webhook.service.webhook.ProcessedEventRepository;

/**
 * MySQL 컨테이너를 띄우고 매 테스트마다 데이터를 비운다.
 *
 * <p>로컬 개발 DB(3307)에 붙이지 않는 이유는 두 가지다. 테스트가 개발 데이터를 지우게 되고,
 * 다른 사람 PC 나 CI 에서는 그 DB 자체가 없다. 컨테이너는 테스트가 스스로 만들어 쓰고 버린다.
 *
 * <p>{@code @Testcontainers}/{@code @Container} 대신 싱글턴 컨테이너를 쓴다. 클래스마다
 * 컨테이너를 껐다 켜면 Spring 이 캐시한 컨텍스트의 커넥션 풀이 죽은 포트를 붙들게 된다.
 * 컨테이너는 JVM 종료 시 Ryuk 이 치운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("webhook_service");

    static {
        MYSQL.start();
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected AccountRepository accountRepository;
    @Autowired
    protected OrderRepository orderRepository;
    @Autowired
    protected ProcessedEventRepository processedEvents;

    /** 외래키 때문에 자식부터 지운다. */
    @BeforeEach
    void cleanDatabase() {
        processedEvents.deleteAll();
        orderRepository.deleteAll();
        accountRepository.deleteAll();
    }
}
