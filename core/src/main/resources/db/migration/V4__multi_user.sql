CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    email          VARCHAR(254) NOT NULL UNIQUE,  -- 소문자로 저장
    password_hash  VARCHAR(100),                  -- NULL 이면 아직 주인이 가입하지 않은 계정
    is_admin       BOOLEAN      NOT NULL DEFAULT FALSE,
    status         VARCHAR(10)  NOT NULL,         -- ACTIVE | DISABLED
    failed_count   INT          NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE invite_code (
    id          BIGSERIAL   PRIMARY KEY,
    code        VARCHAR(16) NOT NULL UNIQUE,
    max_uses    INT         NOT NULL,
    used_count  INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_by  BIGINT      NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE email_token (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(254) NOT NULL,
    purpose         VARCHAR(10)  NOT NULL,   -- VERIFY | RESET
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(100),            -- VERIFY 만
    invite_code_id  BIGINT       REFERENCES invite_code (id),
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE watch_item (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type        VARCHAR(10)  NOT NULL,   -- KEYWORD | AGENCY
    value       VARCHAR(100) NOT NULL,
    cron        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uq_watch_item_value ON watch_item (user_id, type, lower(value));

CREATE TABLE user_filter (
    user_id           BIGINT       PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    exclude_keywords  TEXT         NOT NULL DEFAULT '',  -- 줄바꿈 구분
    categories        VARCHAR(100) NOT NULL,             -- 쉼표 구분
    price_min         BIGINT,
    price_max         BIGINT
);

CREATE TABLE notice (
    source        VARCHAR(16) NOT NULL,
    notice_no     VARCHAR(64) NOT NULL,
    notice_ord    VARCHAR(16) NOT NULL,
    title         TEXT        NOT NULL,
    category      VARCHAR(16),            -- 이관한 행은 NULL
    price         BIGINT,
    agency        TEXT,
    posted_at     TIMESTAMP,
    deadline_at   TIMESTAMP,
    detail_url    TEXT,
    collected_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, notice_no, notice_ord)
);
CREATE INDEX idx_notice_collected ON notice (collected_at);

CREATE TABLE delivery (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    source      VARCHAR(16) NOT NULL,
    notice_no   VARCHAR(64) NOT NULL,
    notice_ord  VARCHAR(16) NOT NULL,
    status      VARCHAR(10) NOT NULL,   -- PENDING | SENT | FAILED
    attempts    INT         NOT NULL DEFAULT 0,
    matched_at  TIMESTAMPTZ NOT NULL,
    sent_at     TIMESTAMPTZ,
    UNIQUE (user_id, source, notice_no, notice_ord),
    FOREIGN KEY (source, notice_no, notice_ord) REFERENCES notice (source, notice_no, notice_ord) ON DELETE CASCADE
);
CREATE INDEX idx_delivery_pending ON delivery (status) WHERE status = 'PENDING';

CREATE TABLE delivery_item (
    delivery_id    BIGINT NOT NULL REFERENCES delivery (id) ON DELETE CASCADE,
    watch_item_id  BIGINT NOT NULL REFERENCES watch_item (id) ON DELETE CASCADE,
    PRIMARY KEY (delivery_id, watch_item_id)
);
CREATE INDEX idx_delivery_item_watch ON delivery_item (watch_item_id);

CREATE TABLE outbox_mail (
    id          BIGSERIAL    PRIMARY KEY,
    to_address  VARCHAR(254) NOT NULL,
    subject     VARCHAR(200) NOT NULL,
    html        TEXT         NOT NULL,
    status      VARCHAR(10)  NOT NULL,   -- PENDING | SENT | FAILED
    attempts    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    sent_at     TIMESTAMPTZ
);
