package dev.gateway.webhook.dispatch;

/**
 * 지터 전략. 설계 8.2.
 *
 * <p>지터가 없으면 소비자가 5분 다운됐다 복구될 때 밀린 이벤트의 재시도 시각이
 * 한 점에 몰려 소비자를 다시 죽인다(thundering herd).
 * 세 전략을 두는 것은 설계 11.2의 복구 곡선 비교를 위해서다.
 */
public enum JitterStrategy {
    /** 순수 지수 백오프. 비교 기준선. */
    NONE,
    /** random(0, base) */
    FULL,
    /** min(max, random(initial, prev * 3)) */
    DECORRELATED
}
