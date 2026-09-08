-- 在 SSO 数据库执行。先由 SSO 运维使用现有 ClientSecretCodec 加密独立生成的 Secret。
-- 替换全部 REPLACE_* 后执行；B 配置原始 Secret，SSO 表存 AES-GCM 密文，不能填明文。
-- 不含 UPDATE/DELETE，重复 client_id 时失败，避免覆盖已登记客户端。
START TRANSACTION;
INSERT INTO sso_client (id,client_id,client_name,client_hmac_key_cipher,client_hmac_key_version,status,transport,backchannel_logout_uri,post_logout_redirect_uri,create_time,update_time)
VALUES (UUID(),'demo-client-b','B 接入示例','REPLACE_AES_GCM_CIPHERTEXT','REPLACE_KEY_VERSION',1,'http','http://REPLACE_B_IP:18080/api/sso/backchannel-logout','http://REPLACE_B_IP:18080/',NOW(),NOW());
INSERT INTO sso_client_redirect_uri (id,client_id,redirect_uri)
VALUES (UUID(),'demo-client-b','http://REPLACE_B_IP:18080/api/auth/callback');
COMMIT;
