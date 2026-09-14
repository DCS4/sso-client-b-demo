-- =========================================================
-- 03-sso-server-schema.sql (SSO 鏈嶅姟绔牳蹇冭〃缁撴瀯涓庣储寮?
-- =========================================================

-- jeecg-sso V1.2 基础表。客户端 HMAC 密钥以 AES-GCM 密文保存：base64(iv):base64(ciphertext)。
CREATE TABLE IF NOT EXISTS sso_client (
  id                       VARCHAR(36)  NOT NULL,
  client_id                VARCHAR(64)  NOT NULL,
  client_name              VARCHAR(128) NOT NULL,
  client_hmac_key_cipher   VARCHAR(1024) NOT NULL,
  client_hmac_key_version  VARCHAR(32)  NOT NULL,
  status                   INT NOT NULL DEFAULT 1,
  transport                VARCHAR(8) NOT NULL DEFAULT 'http',
  backchannel_logout_uri   VARCHAR(512),
  post_logout_redirect_uri VARCHAR(512),
  create_time              DATETIME,
  update_time              DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sso_client_id (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SSO客户端登记';

CREATE TABLE IF NOT EXISTS sso_client_redirect_uri (
  id           VARCHAR(36)  NOT NULL,
  client_id    VARCHAR(64)  NOT NULL,
  redirect_uri VARCHAR(512) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sso_client_redirect (client_id, redirect_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SSO客户端回调地址';

CREATE TABLE IF NOT EXISTS sso_logout_event (
  id            VARCHAR(64) NOT NULL,
  sid           VARCHAR(64) NOT NULL,
  sub           VARCHAR(128) NOT NULL,
  client_id     VARCHAR(64) NOT NULL,
  event_type    VARCHAR(32) NOT NULL DEFAULT 'logout',
  logout_uri    VARCHAR(512) NOT NULL,
  event_payload TEXT NOT NULL,
  status        INT NOT NULL DEFAULT 0 COMMENT '0待发 1成功 2重试 9失败',
  retry_count   INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME,
  claim_token   VARCHAR(64),
  claim_until   DATETIME,
  last_error    VARCHAR(1000),
  delivered_at  DATETIME,
  create_time   DATETIME,
  update_time   DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sso_logout_event (sid, client_id, event_type),
  KEY idx_sso_logout_due (status, next_retry_at),
  KEY idx_sso_logout_claim (status, next_retry_at, claim_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SSO登出通知Outbox';


-- ---------------------------------------------------------
-- Outbox Claim 杩佺Щ鑴氭湰 (楂樺苟鍙戝彂浠剁璁ら涔愯閿佹敮鎸?
-- ---------------------------------------------------------

-- 已执行过 sso-schema.sql 的 SSO 库请单独执行一次本迁移；新库只需执行最新版基础脚本。
ALTER TABLE sso_logout_event
  ADD COLUMN claim_token VARCHAR(64) NULL AFTER next_retry_at,
  ADD COLUMN claim_until DATETIME NULL AFTER claim_token,
  ADD KEY idx_sso_logout_claim (status, next_retry_at, claim_until);
