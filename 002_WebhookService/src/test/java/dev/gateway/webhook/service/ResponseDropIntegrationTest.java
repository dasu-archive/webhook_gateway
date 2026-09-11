package dev.gateway.webhook.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import dev.gateway.webhook.service.chaos.ResponseDropPolicy;
import dev.gateway.webhook.service.payment.domain.Account;
import dev.gateway.webhook.service.payment.domain.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 응답 드롭. 결과표의 "소비자 응답 드롭" 줄을 한 건으로 줄여 본다.
 *
 * <p>MockMvc 로는 연결이 끊기는 걸 볼 수 없다. 진짜 톰캣을 띄우고 게이트웨이와 같은 JDK HttpClient 로 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "webhook.chaos.enabled=true")
class ResponseDropIntegrationTest extends IntegrationTestBase {

    private static final String BODY = "{\"orderId\": 123, \"amount\": 1000}";

    @Value("${local.server.port}")
    int port;
    @Autowired
    ResponseDropPolicy policy;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void given5000BalanceAnd1000Order() {
        accountRepository.save(new Account(1L, 5_000));
        orderRepository.save(new Order(123L, 1L, 1_000));
    }

    @AfterEach
    void stopDropping() {
        policy.setRate(0);
    }

    private HttpResponse<String> deliver(String eventId, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhook/receiver"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Gateway-Event-Id", eventId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int balanceOf(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("드롭하면 결제는 끝났는데 응답이 끊긴다 — 타임아웃이 아니라 끊김이라 게이트웨이는 CONN_RESET 으로 본다")
    void processesThenDropsConnection() {
        policy.setRate(1.0);

        assertThatThrownBy(() -> deliver("evt-drop", BODY))
                .isInstanceOf(IOException.class)
                // HttpTimeoutException 도 IOException 이다. 타임아웃으로 끝나면 드롭이 아니라 느린 소비자다
                .isNotInstanceOf(HttpTimeoutException.class);

        assertThat(balanceOf(1L)).isEqualTo(4_000);
        assertThat(processedEvents.existsById("evt-drop")).isTrue();
        assertThat(policy.dropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("드롭 뒤 같은 이벤트가 다시 오면 200 이고 결제는 한 번뿐이다 — 중복 전달 1, 중복 처리 0")
    void retryAfterDropIsDeduplicated() throws Exception {
        policy.setRate(1.0);
        assertThatThrownBy(() -> deliver("evt-drop", BODY)).isInstanceOf(IOException.class);

        policy.setRate(0);
        assertThat(deliver("evt-drop", BODY).statusCode()).isEqualTo(200);

        assertThat(balanceOf(1L)).isEqualTo(4_000);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("2xx 가 아니면 끊지 않는다 — 드롭은 '처리했는데 알리지 못한' 상황만 흉내 낸다")
    void doesNotDropErrorResponses() throws Exception {
        policy.setRate(1.0);

        var response = deliver("evt-unknown", "{\"orderId\": 999, \"amount\": 1000}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(policy.dropped()).isZero();
    }

    @Test
    @DisplayName("드롭률은 재기동 없이 바꾸고, 바꾸면 드롭 횟수가 0 으로 돌아가며, 범위 밖 값은 400")
    void changesRateAtRuntime() throws Exception {
        mockMvc.perform(post("/chaos/response-drop").param("rate", "0.3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.3))
                .andExpect(jsonPath("$.dropped").value(0));
        mockMvc.perform(get("/chaos/response-drop"))
                .andExpect(jsonPath("$.rate").value(0.3));
        mockMvc.perform(post("/chaos/response-drop").param("rate", "1.5"))
                .andExpect(status().isBadRequest());
    }
}
