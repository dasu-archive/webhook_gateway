package dev.gateway.webhook.common;

import java.util.Locale;
import java.util.Set;

/**
 * 어떤 헤더를 원장에 남기고 소비자에게 다시 실어 보낼지.
 *
 * <p>제공자의 서명 헤더는 반드시 보존해야 한다. PASSTHROUGH 모드에서 소비자가 그 헤더로
 * 원 제공자의 서명을 재검증하기 때문이다(설계 9.5). 그래서 화이트리스트가 아니라
 * 최소 블록리스트를 쓴다 — 무엇이 서명 헤더인지는 제공자마다 다르고 미리 다 알 수 없다.
 */
public final class HeaderPolicy {

    /** 커넥션 단위 헤더. 다음 홉으로 넘기면 안 되고 저장할 가치도 없다. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /** 다음 홉에서 새로 계산되어야 하는 것들. */
    private static final Set<String> REWRITTEN = Set.of(
            "host", "content-length", "expect");

    /** 자격 증명. 원장은 오래 남으므로 애초에 담지 않는다. */
    private static final Set<String> CREDENTIALS = Set.of(
            "authorization", "cookie", "set-cookie");

    private HeaderPolicy() {
    }

    /** 원장에 저장할 헤더인가. */
    public static boolean storable(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP.contains(lower)
                && !REWRITTEN.contains(lower)
                && !CREDENTIALS.contains(lower);
    }

    /** 소비자에게 그대로 실어 보낼 헤더인가. Content-Type 은 전달부가 따로 세팅한다. */
    public static boolean forwardable(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return storable(name) && !lower.equals("content-type") && !lower.startsWith("x-gateway-");
    }
}
