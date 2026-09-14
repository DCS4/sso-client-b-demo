-- 已执行过 sso-schema.sql 的 SSO 库请单独执行一次本迁移；新库只需执行最新版基础脚本。
ALTER TABLE sso_logout_event
  ADD COLUMN claim_token VARCHAR(64) NULL AFTER next_retry_at,
  ADD COLUMN claim_until DATETIME NULL AFTER claim_token,
  ADD KEY idx_sso_logout_claim (status, next_retry_at, claim_until);
