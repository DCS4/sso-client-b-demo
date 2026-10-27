# 数据库与客户端初始化说明

## 1. 为什么接入方（B 系统）自身不需要建表？

本接入示例工程 `sso-client-demo` 将用户信息和页面 Token 放入服务端 `HttpSession`，**客户端自身不需要数据库即可独立运行**。

真实业务系统在接入时，可将用户映射和 Token 元数据存入已有数据库或分布式 Redis 缓存，但**绝不得保存或返回 SSO 用户的密码字段**。

---

## 2. 统一认证中心（SSO 服务端）客户端登记规范

SSO V2 服务端对客户端和页面资源的登记推荐通过统一管控平台（管理后台）创建，其目的在于：
1. **自动生成高强度密钥**：后端调用安全随机数生成 32 字节明文密钥，严禁人工自拟弱口令；
2. **单向哈希安全落库**：数据库只保存 Secret 的 `SHA-256` 摘要，绝不明文存储（杜绝拖库泄露风险）；
3. **接口一次性返回明文**：仅在新增或重置时弹窗展示一次给管理员交接，后续无法反解查询；
4. **规范地址校验**：严格校验 `sys_address` 为基础域名/IP、`callback_address` 为相对路径。

---

## 3. 本地开发/离线测试：直接在 SSO 数据库导入的最新 SQL 模板

如果在开发联调环境中尚未搭建前端管理页面，需要直接在 MySQL / MariaDB 中插入测试客户端数据，**请使用以下最新的 SSO V2 标准表结构与语句**。

> ⚠️ **严禁使用旧版 V1 过期 SQL**：
> 旧版表中的 `select_key`（明文密钥）、`is_enable`、`has_system` 已经**彻底废弃**！若强行执行旧 SQL，会导致字段不存在报错，或者由于 `callback_address` 填写了非相对路径导致客户端被系统判定为不可用。

### 最新 SSO V2 初始化 SQL

```sql
-- ====================================================================
-- 1. 插入 SSO 客户端主记录 (sys_sso 表)
-- 对应客户端：demo_client_b
-- 预设明文密钥：a0db56867afdb9f335eecaf7411cc327
-- 其 SHA-256 值为：5d79ae5ac2c7efb163dab887091b537223993a002aaa694e7c0769c44bcd3415
-- ====================================================================
INSERT INTO jeecg_boot.sys_sso (
    id,
    sys_name,
    client_id,
    client_secret_hash,
    secret_version,
    sys_address,
    callback_address,
    auth_mode,
    status,
    sys_desc
) VALUES (
    '1957990433269182466',
    '业务系统B示例',
    'demo_client_b',                                                      -- 客户端唯一标识，对应配置中的 SSO_CLIENT_ID
    '5d79ae5ac2c7efb163dab887091b537223993a002aaa694e7c0769c44bcd3415',   -- 明文密钥的 SHA-256 散列值（64位小写）
    1,                                                                    -- 密钥版本号，初始为 1
    'http://localhost:18085',                                             -- 系统基础地址 (无末尾斜杠)
    '/api/auth/callback',                                                 -- 唯一固定回调相对路径 (必须以 / 开头！)
    'PAGE_CONTROLLED',                                                    -- 模式：PAGE_CONTROLLED (页面受控) 或 SSO_ONLY (普通单点)
    1,                                                                    -- 状态：1 启用，0 停用
    'SSO V2 客户端接入参考示例'
);

-- ====================================================================
-- 2. 插入受控页面资源记录 (sys_sso_page_resource 表)
-- 仅 PAGE_CONTROLLED 模式需要；若为 SSO_ONLY 模式可不插入
-- 注意：client_id 必须与上方主表的 client_id 完全一致
-- ====================================================================
INSERT INTO jeecg_boot.sys_sso_page_resource (
    id,
    client_id,
    page_code,
    page_name,
    page_path,
    permission_code,
    status,
    sort_no,
    create_time,
    update_time
) VALUES
    ('sso_page_001', 'demo_client_b', 'B_PAGE_01', '订单查询', '/pages/orders', 'b:orders:view', 1, 1, NOW(), NOW()),
    ('sso_page_002', 'demo_client_b', 'B_PAGE_02', '经营报表', '/pages/reports', 'b:reports:view', 1, 2, NOW(), NOW()),
    ('sso_page_003', 'demo_client_b', 'B_PAGE_03', '运维控制台', '/pages/operations', 'b:operations:view', 1, 3, NOW(), NOW());
```

---

## 4. 字段规范与注意事项

1. **`client_secret_hash` 与明文密钥的关系**：
   - 客户端在 `.env` 中填写明文：`SSO_CLIENT_SECRET=a0db56867afdb9f335eecaf7411cc327`。
   - 数据库必须填入该明文的 SHA-256 哈希值：`5d79ae5ac2c7efb163dab887091b537223993a002aaa694e7c0769c44bcd3415`。
   - 双方通信时，SSO 服务端会将收到的明文哈希后与库中比对。
2. **`sys_address` 与 `callback_address`**：
   - `sys_address` 是系统的基础访问 URL，禁止带末尾斜杠；
   - `callback_address` 必须是以 `/` 开头的相对路径。服务端拼接完整回调为：`sys_address + callback_address`。
3. **密钥重置（忘记密钥时的处理）**：
   - 服务端已提供重置接口 `POST /common/sysSso/rotateSecret?id={主键ID}`，调用后自动生成新密钥并返回明文，库内更新为新哈希，`secret_version` 加 1，旧密钥立即作废。
4. **`permission_code` 与 `sys_permission` 权限表关联**：
   - 受控页面记录中的 `permission_code`（如 `b:orders:view`、`b:reports:view`、`b:operations:view`）对应 JeecgBoot 平台 `sys_permission` 权限表中的 `perms` 权限编码字段。
   - 相应权限插入脚本可直接参考同目录下的 [syspermission.sql](syspermission.sql)。用户或其角色必须在 Portal 中被赋予该权限编码，受控校验才能通过。
