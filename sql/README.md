# 数据库与客户端登记说明

## 1. 为什么接入方（B 系统）自身不需要建表？

本接入示例工程 `sso-client-demo` 将用户信息和页面 Token 放入服务端 `HttpSession`，**客户端自身不需要数据库即可独立运行**。

真实业务系统在接入时，可将用户映射和 Token 元数据存入已有数据库或分布式 Redis 缓存，但**绝不得保存或返回 SSO 用户的密码字段**。

---

## 2. 统一认证中心（SSO 服务端）客户端登记与密钥规范

外部系统接入 SSO 时，客户端信息（`client_id`、`sys_address`、`callback_address` 等）统一由管控平台（管理后台）登记与分配：

1. **自动生成高强度密钥**：管理平台在新增系统时自动调用安全随机数生成 32 字节高强度明文密钥，严禁人工编造弱口令；
2. **单向哈希安全落库**：SSO 服务端数据库仅保存该密钥的 `SHA-256` 散列值，绝不明文存储（杜绝拖库泄露风险）；
3. **接口一次性展示交付**：明文密钥仅在创建或重置时弹窗展示一次，由管理员通过安全通道交付给接入方，填入后端的 `SSO_CLIENT_SECRET`；
4. **规范地址校验**：
   - `sys_address` 必须为系统的基础访问 URL，禁止带末尾斜杠（例如：`http://localhost:18085`）；
   - `callback_address` 必须是以 `/` 开头的固定相对路径（例如：`/api/auth/callback`）。服务端拼接完整回调为：`sys_address + callback_address`；
5. **密钥遗忘与轮换**：
   - 若接入方遗失密钥，管理平台提供“重置密钥”接口（`POST /common/sysSso/rotateSecret?id={主键ID}`），调用后重新生成新密钥并返回明文，旧密钥立即作废。

---

## 3. 联调 SQL 脚本使用说明（[syspermission.sql](syspermission.sql)）

同目录下的 [syspermission.sql](syspermission.sql) 为本地联调提供了开箱即用的测试数据。按接入模式不同，执行范围区分如下：

- **普通单点登录（`SSO_ONLY`）**：
  **只需执行其中的 `sys_sso` 客户端登记语句**即可。普通单点登录仅校验用户登录态，不校验页面受控权限，不需要执行页面资源和权限授权 SQL。
- **页面受控模式（`PAGE_CONTROLLED`）**：
  **需要执行文件中的全部 SQL**（包含权限项 `sys_permission`、角色授权 `sys_role_permission`、客户端登记 `sys_sso` 以及受控页面资源 `sys_sso_page_resource`）。

> ⚠️ **角色权限前提（关键）**：
> `syspermission.sql` 脚本默认将受控页面权限赋予了 **`admin` 角色**。
> 因此测试受控模式时，**登录的用户账号必须具备 `admin` 角色权限才能通过页面受控校验**；若使用未授权的普通账号登录，校验接口会拒绝访问（返回 403 页面受控拒绝）。
