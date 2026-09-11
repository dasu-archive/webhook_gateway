-- 재생. README 3.6, 체크리스트 B-1 "재생 API".
--
-- 재생은 새 행을 만들지 않고 DEAD 행을 제자리에서 PENDING 으로 되돌린다.
-- 새 행을 만들면 event.id 가 바뀌어 X-Gateway-Event-Id 가 달라지고, 소비자의 멱등 처리
-- (processed_event PK)가 같은 이벤트를 새 이벤트로 본다. 이미 처리된 건이었다면 두 번 처리된다.
-- 같은 id 로 다시 보내야 소비자 멱등성이 재생에서도 작동한다.
-- 새 행이 원본을 가리키려고 잡아 둔 replay_of 는 그래서 쓸 데가 없다. 값이 들어간 적도 없어 지운다.

ALTER TABLE endpoint
    -- 재생 가드 4번. 소비자가 X-Gateway-Event-Id 로 멱등 처리한다고 선언했는가.
    -- 게이트웨이는 이걸 검증할 수 없다. 선언을 기록하고, 선언이 없으면 재생을 막을 뿐이다.
    ADD COLUMN idempotency_confirmed BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

-- 재생 이력. 누가 · 언제 · 어떤 범위를 · 몇 건.
CREATE TABLE replay (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint_id   BIGINT       NOT NULL,
    received_from DATETIME(3)  NULL,          -- [received_from, received_to). NULL 이면 열린 끝
    received_to   DATETIME(3)  NULL,
    requested_by  VARCHAR(64)  NOT NULL,      -- 어드민 토큰이 공유라 사람을 알 길이 없다. 요청자가 적는다
    reason        VARCHAR(255) NOT NULL,
    event_count   INT          NOT NULL,
    created_at    DATETIME(3)  NOT NULL,
    KEY idx_replay_endpoint (endpoint_id, created_at),
    CONSTRAINT fk_replay_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoint (id)
) ENGINE=InnoDB;

ALTER TABLE event
    DROP COLUMN replay_of,
    -- 재생 가드 2번. 0 이 아니면 이미 재생됐다. 재생 뒤 다시 죽은 건은 사람이 따로 봐야 한다
    ADD COLUMN replay_count   INT    NOT NULL DEFAULT 0,
    ADD COLUMN last_replay_id BIGINT NULL,
    ADD CONSTRAINT fk_event_replay FOREIGN KEY (last_replay_id) REFERENCES replay (id);

ALTER TABLE delivery_attempt
    -- 재생하면 attempt_count 가 0 으로 돌아가 attempt_no 가 1 부터 다시 시작한다.
    -- 원래 시도(NULL)와 재생 뒤 시도를 이 값으로 가른다.
    ADD COLUMN replay_id BIGINT NULL AFTER attempt_no,
    ADD CONSTRAINT fk_attempt_replay FOREIGN KEY (replay_id) REFERENCES replay (id);
