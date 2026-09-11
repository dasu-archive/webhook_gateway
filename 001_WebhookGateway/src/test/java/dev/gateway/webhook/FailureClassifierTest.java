package dev.gateway.webhook;

import dev.gateway.webhook.dispatch.FailureClassifier;
import dev.gateway.webhook.ledger.FailureClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예외 체인은 JDK 21 HttpClient 로 실측한 모양을 그대로 재현했다({@link FailureClassifier} 주석).
 * 실제 소켓으로 도는 검증은 DispatchIntegrationTest 에 있다.
 */
class FailureClassifierTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "200, SUCCESS", "204, SUCCESS",
            "400, HTTP_4XX", "408, HTTP_4XX", "429, HTTP_4XX",
            "500, HTTP_5XX", "503, HTTP_5XX",
            "101, HTTP_OTHER", "302, HTTP_OTHER"
    })
    @DisplayName("상태 코드는 대역으로 나눈다 — 408·429 도 원인은 4xx 다. 재시도 여부는 판정의 몫이다")
    void classifiesStatus(int status, FailureClass expected) {
        assertThat(FailureClassifier.ofStatus(status)).isEqualTo(expected);
    }

    @Test
    @DisplayName("DNS 실패는 최상위가 ConnectException 이어도 DNS_FAIL — 원인 체인을 봐야 커넥션 거부와 갈린다")
    void dnsFailureLooksLikeConnectException() {
        var e = chain(new ConnectException(), new ConnectException(), new UnresolvedAddressException());

        assertThat(FailureClassifier.ofException(e)).isEqualTo(FailureClass.DNS_FAIL);
    }

    @Test
    @DisplayName("커넥션 거부는 CONN_REFUSED")
    void connectionRefused() {
        var e = chain(new ConnectException(), new ConnectException(), new ClosedChannelException());

        assertThat(FailureClassifier.ofException(e)).isEqualTo(FailureClass.CONN_REFUSED);
    }

    @Test
    @DisplayName("연결 타임아웃은 HttpTimeoutException 의 하위 타입이고 체인에 ConnectException 도 있지만 CONNECT_TIMEOUT")
    void connectTimeoutWinsOverBothParents() {
        var e = chain(new HttpConnectTimeoutException("HTTP connect timed out"),
                new ConnectException("HTTP connect timed out"));

        assertThat(FailureClassifier.ofException(e)).isEqualTo(FailureClass.CONNECT_TIMEOUT);
    }

    @Test
    @DisplayName("응답 타임아웃은 RESPONSE_TIMEOUT")
    void responseTimeout() {
        assertThat(FailureClassifier.ofException(new HttpTimeoutException("request timed out")))
                .isEqualTo(FailureClass.RESPONSE_TIMEOUT);
    }

    @Test
    @DisplayName("연결된 뒤 끊기면 CONN_RESET — 응답 없이 닫기 · 바디 도중 닫기 · RST 셋 다")
    void connectionDroppedAfterConnect() {
        var noBytes = new IOException("HTTP/1.1 header parser received no bytes", new EOFException("EOF reached while reading"));
        var truncated = new IOException("fixed content-length: 100, bytes received: 3", new EOFException("EOF reached while reading"));
        var reset = new IOException("HTTP/1.1 header parser received no bytes", new SocketException("Connection reset"));

        assertThat(FailureClassifier.ofException(noBytes)).isEqualTo(FailureClass.CONN_RESET);
        assertThat(FailureClassifier.ofException(truncated)).isEqualTo(FailureClass.CONN_RESET);
        assertThat(FailureClassifier.ofException(reset)).isEqualTo(FailureClass.CONN_RESET);
    }

    @Test
    @DisplayName("TLS 실패는 IOException 이지만 끊김이 아니라 OTHER")
    void tlsFailureIsNotReset() {
        assertThat(FailureClassifier.ofException(new SSLHandshakeException("PKIX path building failed")))
                .isEqualTo(FailureClass.OTHER);
    }

    @Test
    @DisplayName("IOException 이 아닌 예외는 OTHER")
    void unknown() {
        assertThat(FailureClassifier.ofException(new IllegalStateException("?"))).isEqualTo(FailureClass.OTHER);
    }

    /** 앞에서부터 차례로 원인으로 잇는다. */
    private static Throwable chain(Throwable... links) {
        for (int i = 0; i < links.length - 1; i++) {
            links[i].initCause(links[i + 1]);
        }
        return links[0];
    }
}
