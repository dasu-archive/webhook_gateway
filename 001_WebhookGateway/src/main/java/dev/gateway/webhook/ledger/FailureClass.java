package dev.gateway.webhook.ledger;

/**
 * 전달 시도가 어느 단계에서 끝났는가. 체크리스트 B-1.
 *
 * <p>{@link DeliveryOutcome} 과 다른 축이다. outcome 은 판정(재시도할지)이고 이건 원인이다.
 * 429 는 HTTP_4XX 지만 RETRY 이고, 재시도를 다 쓴 HTTP_5XX 는 DEAD 다.
 *
 * <p>네트워크 실패는 HTTP 교환의 단계 순서로 나눴다 — 이름 해석, 연결, 응답 대기, 응답 수신.
 * 어느 단계에서 끊겼는지가 곧 "소비자가 없다 / 느리다 / 받다가 끊었다"의 구분이다.
 */
public enum FailureClass {
    /** 2xx */
    SUCCESS,
    /** 4xx. 408·429 를 빼면 재시도하지 않는다 */
    HTTP_4XX,
    /** 5xx */
    HTTP_5XX,
    /** 1xx·3xx. 리다이렉트를 따라가지 않으므로 3xx 가 여기 온다 */
    HTTP_OTHER,
    /** 호스트 이름을 해석하지 못했다. 설정 오류이거나 소비자 서비스 자체가 사라졌다 */
    DNS_FAIL,
    /** 주소는 찾았지만 연결을 거부당했다. 소비자 프로세스가 내려가 있다 */
    CONN_REFUSED,
    /** connect-timeout 안에 연결이 안 됐다. 네트워크 단절이나 과부하 */
    CONNECT_TIMEOUT,
    /** 연결은 됐지만 request-timeout 안에 응답이 끝나지 않았다. 소비자가 느리다 */
    RESPONSE_TIMEOUT,
    /** 연결된 뒤 응답을 다 받기 전에 끊겼다. 응답 드롭이 여기로 온다 */
    CONN_RESET,
    /** 위 어디에도 맞지 않는다. error_message 를 볼 것 */
    OTHER
}
