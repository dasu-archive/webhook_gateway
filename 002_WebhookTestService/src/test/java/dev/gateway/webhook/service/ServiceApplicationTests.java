package dev.gateway.webhook.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 애플리케이션 컨텍스트가 실제로 뜨는지만 본다.
 *
 * <p>아무것도 검증하지 않는 것처럼 보이지만, 빈 주입이 어긋나거나 구현체가 없는 상태를
 * 잡아낸다. 다른 테스트들은 {@code @MockitoBean} 이 빈자리를 메워 주기 때문에
 * "테스트는 통과하는데 앱은 안 뜨는" 상태를 알아채지 못한다.
 */
class ServiceApplicationTests extends IntegrationTestBase {

    @Test
    @DisplayName("컨텍스트가 로딩된다")
    void contextLoads() {
    }
}
