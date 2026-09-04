package dev.gateway.webhook.receive;

import java.util.ArrayList;
import java.util.List;

/**
 * 검증에 시도할 시크릿 묶음. 설계 6.2 "시크릿 로테이션".
 *
 * <p>시크릿 컬럼이 하나면 로테이션 자체가 불가능하다. 교체 순간 구 시크릿으로 서명된
 * 재시도가 전부 401 이 되기 때문이다. 유예 기간 동안은 둘 다 받아준다.
 */
public record EndpointSecrets(byte[] current, byte[] previous) {

    public static EndpointSecrets of(byte[] current) {
        return new EndpointSecrets(current, null);
    }

    public List<byte[]> candidates() {
        var out = new ArrayList<byte[]>(2);
        out.add(current);
        if (previous != null) {
            out.add(previous);
        }
        return out;
    }
}
