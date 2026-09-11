-- 시도별 실패 종류와 대기 시간. 체크리스트 B-1 "outcome 확장".
--
-- outcome(DELIVERED/RETRY/DEAD)은 그대로 둔다. 그건 "그래서 어떻게 했나"(판정)이고
-- failure_class 는 "무슨 일이 있었나"(원인)다. 두 축은 독립이다 — 429 는 HTTP_4XX 인데 RETRY 이고,
-- 재시도를 다 쓴 HTTP_5XX 는 DEAD 다. 한 컬럼으로 합치면 둘 중 하나를 잃는다.
--
-- 기존 행은 원인을 되살릴 근거가 error_message 문자열뿐이라 NULL 로 남긴다. NULL = V2 이전 시도.
ALTER TABLE delivery_attempt
    ADD COLUMN failure_class ENUM('SUCCESS', 'HTTP_4XX', 'HTTP_5XX', 'HTTP_OTHER',
                                  'DNS_FAIL', 'CONN_REFUSED', 'CONNECT_TIMEOUT',
                                  'RESPONSE_TIMEOUT', 'CONN_RESET', 'OTHER') NULL AFTER outcome,
    -- 이 시도 뒤에 기다리기로 한 시간. RETRY 일 때만 값이 있다. 지터 3종 복구 곡선 비교(A-2)에 쓴다.
    ADD COLUMN backoff_ms INT NULL AFTER failure_class,
    ADD KEY idx_attempt_started (started_at, failure_class);
