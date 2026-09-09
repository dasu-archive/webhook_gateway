package dev.gateway.webhook;

import dev.gateway.webhook.common.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GatewayProperties.class)
public class WebhookGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookGatewayApplication.class, args);
    }
}
