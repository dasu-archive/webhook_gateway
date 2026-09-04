package dev.gateway.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 8.0 컨테이너를 띄우고 매 테스트마다 원장을 비운다.
 *
 * <p>H2 로 대체하지 않는 이유는 검증 대상 자체가 MySQL 기능이기 때문이다 —
 * {@code FOR UPDATE SKIP LOCKED}(8.0 전용)와 유니크 인덱스 기반 중복 차단.
 *
 * <p>{@code @Testcontainers}/{@code @Container} 대신 싱글턴 컨테이너를 쓴다. 클래스마다
 * 컨테이너를 껐다 켜면 Spring 이 캐시한 컨텍스트의 커넥션 풀이 죽은 포트를 붙들게 된다.
 * 컨테이너는 JVM 종료 시 Ryuk 이 치운다.
 */
@SpringBootTest(properties = {
        // @Scheduled 워커를 끄고 테스트가 직접 한 사이클씩 돌린다. 타이밍 의존을 없애기 위해서다.
        "gateway.dispatch.enabled=false",
        "gateway.dispatch.backoff.jitter=NONE",
        "logging.level.dev.gateway.webhook=DEBUG"
})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("webhook_gateway");

    static {
        MYSQL.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void truncateLedger() {
        jdbc.sql("DELETE FROM delivery_attempt").update();
        jdbc.sql("DELETE FROM event").update();
        jdbc.sql("DELETE FROM endpoint").update();
    }
}
