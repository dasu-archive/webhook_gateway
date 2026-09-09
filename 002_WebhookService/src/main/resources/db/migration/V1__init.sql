-- 결제 대금이 빠져나가는 계좌
CREATE TABLE account (
    id      BIGINT NOT NULL PRIMARY KEY,
    balance INT    NOT NULL
) ENGINE = InnoDB;

-- 결제 대상 주문. order 는 MySQL 예약어라 테이블명은 orders 를 쓴다.
CREATE TABLE orders (
    id         BIGINT NOT NULL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount     INT    NOT NULL,
    CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB;

-- 이미 처리한 게이트웨이 이벤트. 게이트웨이 전달은 at-least-once 라 같은
-- X-Gateway-Event-Id 가 반드시 두 번 이상 온다. PK 가 중복 결제의 최후 방어선이다.
CREATE TABLE processed_event (
    gateway_event_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    order_id         BIGINT       NOT NULL,
    processed_at     DATETIME(3)  NOT NULL
) ENGINE = InnoDB;
