package dev.gateway.webhook.admin;

import dev.gateway.webhook.common.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 관리 평면 인증. 공유 토큰 하나뿐인 최소 구현이다.
 *
 * <p>수신 경로({@code /webhooks/**})는 서명으로 인증하므로 여기 걸리지 않는다.
 * gateway.admin.token 이 비어 있으면 필터를 아예 등록하지 않는다 — 로컬 검증 환경에서
 * 토큰을 요구하면 설계 11절의 반복 실험이 번거로워진다. 운영에서는 반드시 설정한다.
 */
@Configuration
public class AdminSecurityConfig {

    static final String HEADER = "X-Admin-Token";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> adminTokenFilter(GatewayProperties properties) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setFilter(new TokenFilter(properties.getAdmin().getToken()));
        registration.addUrlPatterns("/admin/*");
        registration.setEnabled(!properties.getAdmin().getToken().isBlank());
        return registration;
    }

    private static final class TokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        private TokenFilter(String token) {
            this.expected = token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String presented = request.getHeader(HEADER);
            if (presented == null
                    || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":\"unauthorized\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
