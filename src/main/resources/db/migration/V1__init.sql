CREATE TABLE notified_notice (
    source       VARCHAR(16) NOT NULL,
    notice_no    VARCHAR(64) NOT NULL,
    notice_ord   VARCHAR(16) NOT NULL,
    title        TEXT        NOT NULL,
    notified_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, notice_no, notice_ord)
);

CREATE TABLE job_state (
    job_name             VARCHAR(32) PRIMARY KEY,
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    alert_sent           BOOLEAN     NOT NULL DEFAULT FALSE
);
