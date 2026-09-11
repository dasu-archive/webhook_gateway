package dev.gateway.webhook.dispatch;

import dev.gateway.webhook.ledger.FailureClass;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * 전달 시도의 결과를 {@link FailureClass} 로 나눈다.
 *
 * <p>예외 분류는 <b>최상위 타입이 아니라 원인 체인</b>을 본다. JDK 21 HttpClient 실측 결과다.
 * <pre>
 * DNS 실패          ConnectException(null) → ConnectException(null) → UnresolvedAddressException
 * 커넥션 거부        ConnectException(null) → ConnectException(null) → ClosedChannelException
 * 연결 타임아웃      HttpConnectTimeoutException → … → ConnectException
 * 응답 타임아웃      HttpTimeoutException
 * 응답 없이 끊음     IOException("header parser received no bytes") → EOFException
 * 바디 도중 끊음     IOException("fixed content-length …") → EOFException
 * RST              IOException("header parser received no bytes") → SocketException("Connection reset")
 * </pre>
 * DNS 실패와 커넥션 거부는 최상위가 같고 메시지도 null 이라, 체인을 보지 않으면 구분이 안 된다.
 * 연결 타임아웃은 {@link HttpTimeoutException} 의 하위 타입이고 체인에 ConnectException 도 있어서
 * 검사 순서가 곧 규칙이다.
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    public static FailureClass ofStatus(int status) {
        if (status >= 200 && status < 300) {
            return FailureClass.SUCCESS;
        }
        if (status >= 400 && status < 500) {
            return FailureClass.HTTP_4XX;
        }
        if (status >= 500 && status < 600) {
            return FailureClass.HTTP_5XX;
        }
        return FailureClass.HTTP_OTHER;
    }

    public static FailureClass ofException(Throwable e) {
        if (inChain(e, UnresolvedAddressException.class) || inChain(e, UnknownHostException.class)) {
            return FailureClass.DNS_FAIL;
        }
        if (inChain(e, HttpConnectTimeoutException.class)) {
            return FailureClass.CONNECT_TIMEOUT;
        }
        if (inChain(e, HttpTimeoutException.class)) {
            return FailureClass.RESPONSE_TIMEOUT;
        }
        if (inChain(e, ConnectException.class)) {
            return FailureClass.CONN_REFUSED;
        }
        if (inChain(e, SSLException.class)) {
            return FailureClass.OTHER;
        }
        // 연결 단계 실패는 위에서 다 걸렀다. 남은 IOException 은 연결된 뒤에 끊긴 것이다.
        if (e instanceof IOException) {
            return FailureClass.CONN_RESET;
        }
        return FailureClass.OTHER;
    }

    private static boolean inChain(Throwable e, Class<? extends Throwable> type) {
        // 원인 체인이 순환하는 예외도 있다. 깊이로 끊는다.
        int depth = 0;
        for (Throwable t = e; t != null && depth < 16; t = t.getCause(), depth++) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
