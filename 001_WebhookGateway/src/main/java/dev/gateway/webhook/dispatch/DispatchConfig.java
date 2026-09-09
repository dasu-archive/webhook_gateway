package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.common.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DispatchConfig {

    /**
     * 시계를 빈으로 두는 이유는 테스트 때문만이 아니다. 타임스탬프 윈도우 검증이
     * 서버 시계에 의존하므로(설계 13절 "시계 오차") 시간 출처를 한 곳으로 모아둔다.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient dispatchHttpClient(GatewayProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getDispatch().getConnectTimeout())
                // 리다이렉트를 따라가지 않는다. 소비자 엔드포인트는 고정 주소여야 하고,
                // 따라가면 웹훅 원본이 의도치 않은 곳으로 흘러갈 수 있다.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 고정 크기 풀이다. 가상 스레드로 무제한 띄우면 소비자가 느려질 때 게이트웨이가
     * 소비자를 더 세게 때린다. 워커 수가 곧 소비자에 대한 동시 요청 상한이다.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService dispatchExecutor(GatewayProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getDispatch().getWorkerThreads(),
                Thread.ofPlatform().name("dispatch-", 0).factory());
    }
}
