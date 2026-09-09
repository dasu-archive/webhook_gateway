package dev.gateway.webhook.receive.signature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 계산과 상수 시간 비교. 설계 6.2. */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private Hmac() {
    }

    public static byte[] sha256(byte[] secret, byte[] message) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    public static String hexSha256(byte[] secret, byte[] message) {
        return HEX.formatHex(sha256(secret, message));
    }

    /**
     * 상수 시간 비교. equals() 는 첫 불일치에서 조기 반환하므로 응답 시간 차이로
     * 서명을 한 바이트씩 추론할 수 있다.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    public static String sha256Hex(byte[] message) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(message));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 계산 실패", e);
        }
    }
}
