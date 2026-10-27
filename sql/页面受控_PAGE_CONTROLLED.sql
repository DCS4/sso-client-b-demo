-- ====================================================================
-- 页面受控模式 (PAGE_CONTROLLED) 本地联调脚本
-- 在 SSO 管理方（yth / jeecg_boot 库）执行，需要整份脚本：
--   1) 权限项  2) 授权给 admin 角色  3) 登记客户端  4) 登记受控页面
-- 测试时登录账号必须拥有 admin 角色，否则页面会被拒绝。
--
-- 1. auth_mode 存储为数值 0（对应字典 sso 中的“页面受控”）；
-- 2. client_secret_hash 是明文密钥的 SHA-256 密文；
--    明文就是接入方 .env 中的 SSO_CLIENT_SECRET，数据库里不保存明文。
-- 3. 若 sso_demo_001 已存在（例如先执行过普通单点登录脚本），请先：
--    DELETE FROM sys_sso WHERE id = 'sso_demo_001';
-- ====================================================================

-- 1. 权限项 (sys_permission)
INSERT INTO sys_permission
(id, parent_id, name, url, component, is_route, component_name, redirect,
 menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf,
 keep_alive, hidden, hide_tab, description, create_by, create_time,
 update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES
    ('sso_perm_001', '', '外部B系统-订单查询', '/pages/orders', NULL, 1, NULL, NULL,
     2, 'b:orders:view', '1', 10.0, NULL, NULL, 1, NULL, 0, NULL, NULL,
     'admin', '2026-09-20 21:05:34.000', NULL, NULL, 0, 0, '1', NULL),
    ('sso_perm_002', '', '外部B系统-经营报表', '/pages/reports', NULL, 1, NULL, NULL,
     2, 'b:reports:view', '1', 20.0, NULL, NULL, 1, NULL, 0, NULL, NULL,
     'admin', '2026-09-20 21:05:34.000', NULL, NULL, 0, 0, '1', NULL),
    ('sso_perm_003', '', '外部B系统-运维控制台', '/pages/operations', NULL, 1, NULL, NULL,
     2, 'b:operations:view', '1', 30.0, NULL, NULL, 1, NULL, 0, NULL, NULL,
     'admin', '2026-09-20 21:05:34.000', NULL, NULL, 0, 0, '1', NULL);

-- 2. 把权限授予 admin 角色 (sys_role_permission)，角色 id 从 sys_role 动态取
INSERT INTO sys_role_permission
(id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT 'sso_rp_001', r.id, 'sso_perm_001', NULL, NULL, NULL
FROM sys_role r
WHERE r.role_code = 'admin' AND r.tenant_id = 0;

INSERT INTO sys_role_permission
(id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT 'sso_rp_002', r.id, 'sso_perm_002', NULL, NULL, NULL
FROM sys_role r
WHERE r.role_code = 'admin' AND r.tenant_id = 0;

INSERT INTO sys_role_permission
(id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT 'sso_rp_003', r.id, 'sso_perm_003', NULL, NULL, NULL
FROM sys_role r
WHERE r.role_code = 'admin' AND r.tenant_id = 0;

-- 3. 登记客户端 (sys_sso)
INSERT INTO sys_sso
(id, sys_name, callback_address, client_id, client_secret_hash, secret_version, sys_address, sys_desc, auth_mode, status, create_time, update_time)
VALUES
    ('sso_demo_001', 'SSO客户端B系统Demo', '/api/auth/callback', 'demo_client_b',
     'afc6bfb26a94fd3a7780bbca05fc5aea15906c53563336133d2546c3b243e826', 1,
     'http://localhost:18085', '用于 sso-client-demo 本地联调测试', 0, 1,
     '2026-09-20 20:26:34', '2026-09-23 09:55:41');

-- 4. 登记受控页面 (sys_sso_page_resource)
INSERT INTO sys_sso_page_resource
(id, client_id, page_code, page_name, page_path, permission_code, status, sort_no, create_time, update_time)
VALUES
    ('sso_page_001', 'demo_client_b', 'B_PAGE_01', '订单查询', '/pages/orders', 'b:orders:view', 1, 1, '2026-09-20 20:26:48', '2026-09-20 20:26:48'),
    ('sso_page_002', 'demo_client_b', 'B_PAGE_02', '经营报表', '/pages/reports', 'b:reports:view', 1, 2, '2026-09-20 20:26:48', '2026-09-20 20:26:48'),
    ('sso_page_003', 'demo_client_b', 'B_PAGE_03', '运维控制台', '/pages/operations', 'b:operations:view', 1, 3, '2026-09-20 20:26:48', '2026-09-20 20:26:48');
