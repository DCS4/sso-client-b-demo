-- 已执行过 sso-user-status-event.sql 的 System 库请单独执行一次本迁移；新库只需执行最新版基础脚本。
ALTER TABLE user_status_event
  ADD COLUMN claim_token VARCHAR(64) NULL AFTER next_retry_at,
  ADD COLUMN claim_until DATETIME NULL AFTER claim_token,
  ADD KEY idx_user_status_event_claim (status, next_retry_at, claim_until);
