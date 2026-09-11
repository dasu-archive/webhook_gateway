package dev.gateway.webhook.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 카오스 장치는 켜지 않으면 존재하지 않는다. 운영에 실수로 딸려 나가도 아무것도 끊지 않는다. */
class ChaosDisabledByDefaultTest extends IntegrationTestBase {

    @Test
    @DisplayName("webhook.chaos.enabled 가 없으면 제어 엔드포인트도 없다")
    void chaosEndpointIsAbsentByDefault() throws Exception {
        mockMvc.perform(get("/chaos/response-drop"))
                .andExpect(status().isNotFound());
    }
}
