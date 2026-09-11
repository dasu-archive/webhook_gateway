package dev.gateway.webhook.ledger;

/** 재생 범위에 든 DEAD 한 건의 판정. 가드 1·2번이 건 단위로 걸리는 자리다. */
public enum ReplayVerdict {
    /** 재생한다 */
    ELIGIBLE,
    /** 가드 2번. 이미 한 번 재생됐다 */
    ALREADY_REPLAYED,
    /** 가드 1번. 4xx(408·429 제외)로 죽었다. 다시 보내도 같은 결과다 */
    PERMANENT_4XX
}
