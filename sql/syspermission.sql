-- ====================================================================
-- SSO Client Demo 本地联调数据库初始化脚本
--
-- 【执行模式说明】：
-- 1. 普通单点登录模式 (SSO_ONLY)：
--    只需要执行【第 3 节：sys_sso 客户端登记】，无需执行页面资源与权限授权。
--
-- 2. 页面受控模式 (PAGE_CONTROLLED)：
--    需要执行本文件中的【全部 SQL】（包含权限项、角色授权、客户端及页面资源）。
--
-- 3. 测试账号权限说明：
--    本脚本第 2 节默认将受控页面权限赋予了【admin 角色】。
--    页面受控模式测试时，登录的用户账号必须具备 admin 角色权限才能通过页面校验。
-- ====================================================================

-- --------------------------------------------------------------------
-- 1. 插入受控页面权限项 (sys_permission 表) - 仅 PAGE_CONTROLLED 需要
-- --------------------------------------------------------------------
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
     'admin', '2026-09-20 21:05:34.000', NULL, NULL, 1, 0, '1', NULL);


-- --------------------------------------------------------------------
-- 2. 将受控页面权限赋予 admin 角色 (sys_role_permission 表) - 仅 PAGE_CONTROLLED 需要
-- --------------------------------------------------------------------
INSERT INTO sys_role_permission
(id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT
    'sso_rp_001',
    r.id,                -- 从角色表动态取 admin 角色的主键 ID
    'sso_perm_001',
    NULL, NULL, NULL
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


-- --------------------------------------------------------------------
-- 3. 插入 SSO 客户端主记录 (sys_sso 表) - 两种模式均需要（SSO_ONLY 仅需此段）
-- --------------------------------------------------------------------
INSERT INTO jeecg_boot.sys_sso
(id, sys_name, callback_address, client_id, client_secret_hash, secret_version, sys_address, sys_desc, auth_mode, status, create_time, update_time)
VALUES
    ('sso_demo_001', 'SSO客户端B系统Demo', '/api/auth/callback', 'demo_client_b',
     'afc6bfb26a94fd3a7780bbca05fc5aea15906c53563336133d2546c3b243e826', 1,
     'http://localhost:18085', '用于 sso-client-demo 本地联调测试', 'PAGE_CONTROLLED', 1,
     '2026-09-20 20:26:34', '2026-09-23 09:55:41');


-- --------------------------------------------------------------------
-- 4. 插入受控页面资源记录 (sys_sso_page_resource 表) - 仅 PAGE_CONTROLLED 需要
-- --------------------------------------------------------------------
INSERT INTO jeecg_boot.sys_sso_page_resource
(id, client_id, page_code, page_name, page_path, permission_code, status, sort_no, create_time, update_time)
VALUES
    ('sso_page_001', 'demo_client_b', 'B_PAGE_01', '订单查询', '/pages/orders', 'b:orders:view', 1, 1, '2026-09-20 20:26:48', '2026-09-20 20:26:48'),
    ('sso_page_002', 'demo_client_b', 'B_PAGE_02', '经营报表', '/pages/reports', 'b:reports:view', 1, 2, '2026-09-20 20:26:48', '2026-09-20 20:26:48'),
    ('sso_page_003', 'demo_client_b', 'B_PAGE_03', '运维控制台', '/pages/operations', 'b:operations:view', 1, 3, '2026-09-20 20:26:48', '2026-09-20 20:26:48');
