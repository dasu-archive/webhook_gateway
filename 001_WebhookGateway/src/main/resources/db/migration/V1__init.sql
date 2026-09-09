-- 원장(ledger) 스키마. 설계 문서 7.1 기준.
-- 게이트웨이는 페이로드를 해석하지 않으므로 raw_body 는 끝까지 바이트로만 다룬다(P6).

CREATE TABLE endpoint (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    slug              VARCHAR(64)    NOT NULL,
    provider          VARCHAR(32)    NOT NULL,
    secret_current    VARBINARY(255) NOT NULL,
    secret_previous   VARBINARY(255) NULL,
    secret_rotated_at DATETIME(3)    NULL,
    target_url        VARCHAR(1024)  NOT NULL,
    signature_mode    ENUM('PASSTHROUGH') NOT NULL DEFAULT 'PASSTHROUGH',
    max_attempts      INT            NOT NULL DEFAULT 8,
    retention_days    INT            NOT NULL DEFAULT 7,
    enabled           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        DATETIME(3)    NOT NULL,
    UNIQUE KEY uk_endpoint_slug (slug)
) ENGINE=InnoDB;

CREATE TABLE event (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    endpoint_id        BIGINT       NOT NULL,
    idempotency_key    VARCHAR(255) NOT NULL,
    idempotency_source ENUM('PROVIDER_ID','BODY_HASH') NOT NULL,
    raw_body           MEDIUMBLOB   NOT NULL,
    raw_headers        JSON         NOT NULL,
    content_type       VARCHAR(128) NULL,
    body_size          INT          NOT NULL,
    status             ENUM('PENDING','DELIVERING','DELIVERED','DEAD') NOT NULL,
    attempt_count      INT          NOT NULL DEFAULT 0,
    last_backoff_ms    INT          NULL,
    next_attempt_at    DATETIME(3)  NULL,
    received_at        DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    replay_of          BIGINT       NULL,
    UNIQUE KEY uk_event_idem (endpoint_id, idempotency_key),
    KEY idx_event_dispatch (status, next_attempt_at),
    KEY idx_event_received (endpoint_id, received_at),
    KEY idx_event_zombie (status, updated_at),
    CONSTRAINT fk_event_endpoint FOREIGN KEY (endpoint_id) REFERENCES endpoint (id)
) ENGINE=InnoDB;

CREATE TABLE delivery_attempt (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT       NOT NULL,
    attempt_no      INT          NOT NULL,
    started_at      DATETIME(3)  NOT NULL,
    duration_ms     INT          NULL,
    response_status INT          NULL,
    outcome         ENUM('DELIVERED','RETRY','DEAD') NOT NULL,
    error_message   VARCHAR(512) NULL,
    KEY idx_attempt_event (event_id, attempt_no),
    CONSTRAINT fk_attempt_event FOREIGN KEY (event_id) REFERENCES event (id)
) ENGINE=InnoDB;
