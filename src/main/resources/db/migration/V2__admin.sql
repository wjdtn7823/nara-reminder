CREATE TABLE filter_term (
    id          BIGSERIAL    PRIMARY KEY,
    type        VARCHAR(20)  NOT NULL,   -- KEYWORD | EXCLUDE_KEYWORD | AGENCY
    value       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (type, value)
);

CREATE TABLE filter_setting (
    id          SMALLINT     PRIMARY KEY CHECK (id = 1),
    categories  VARCHAR(100) NOT NULL,   -- 쉼표 구분, 예: GOODS,SERVICE
    price_min   BIGINT,
    price_max   BIGINT,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE job_run (
    id             BIGSERIAL   PRIMARY KEY,
    job_name       VARCHAR(32) NOT NULL,  -- BID_POLLING | PRE_SPEC_DAILY
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ NOT NULL,
    window_from    TIMESTAMPTZ NOT NULL,
    window_to      TIMESTAMPTZ NOT NULL,
    fetched_count  INT         NOT NULL,  -- API 조회 건수 (업무구분 합계)
    matched_count  INT         NOT NULL,  -- 필터 통과 + 미발송 건수
    sent_count     INT         NOT NULL,  -- 메일 발송·기록된 건수
    status         VARCHAR(10) NOT NULL,  -- SUCCESS | FAILURE
    error          TEXT
);
CREATE INDEX idx_job_run_started ON job_run (started_at DESC);
