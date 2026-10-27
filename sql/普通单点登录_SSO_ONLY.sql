-- ====================================================================
-- 普通单点登录模式 (SSO_ONLY) 本地联调脚本
-- 在 SSO 管理方（yth / jeecg_boot 库）执行，只需登记客户端，无需页面与权限数据。
--
-- 1. auth_mode 存储为数值 1（对应字典 sso 中的“普通单点登录”）；
-- 2. client_secret_hash 是明文密钥的 SHA-256 密文；
--    明文就是接入方 .env 中的 SSO_CLIENT_SECRET，数据库里不保存明文。
-- 3. 若 sso_demo_001 已存在（例如先执行过页面受控脚本），请先：
--    DELETE FROM sys_sso WHERE id = 'sso_demo_001';
-- ====================================================================
INSERT INTO sys_sso
(id, sys_name, callback_address, client_id, client_secret_hash, secret_version, sys_address, sys_desc, auth_mode, status, create_time, update_time)
VALUES
    ('sso_demo_001', 'SSO客户端B系统Demo', '/api/auth/callback', 'demo_client_b',
     'afc6bfb26a94fd3a7780bbca05fc5aea15906c53563336133d2546c3b243e826', 1,
     'http://localhost:18085', '用于 sso-client-demo 本地联调测试', 1, 1,
     '2026-09-20 20:26:34.000', '2026-10-27 16:08:59.000');
