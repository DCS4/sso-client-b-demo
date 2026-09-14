-- System 用户状态事件 Outbox。请在 System 库执行；不与 SSO 库混用。
CREATE TABLE IF NOT EXISTS user_status_event (
    id              VARCHAR(32)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    subject         VARCHAR(64)  NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 0,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NOT NULL,
    claim_token     VARCHAR(64)  NULL,
    claim_until     DATETIME     NULL,
    last_error      VARCHAR(1000) NULL,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    PRIMARY KEY (id),
  KEY idx_user_status_event_due (status, next_retry_at),
  KEY idx_user_status_event_claim (status, next_retry_at, claim_until),
  KEY idx_user_status_event_subject_order (subject, status, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System 到 SSO 的用户状态事件 Outbox';
