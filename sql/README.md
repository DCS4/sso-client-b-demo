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

## 3. 页面权限编码（仅页面受控模式）

在“页面受控模式（`PAGE_CONTROLLED`）”下，测试用户需要在统一认证中心被赋予对应的页面权限编码（如 `b:orders:view`、`b:reports:view`、`b:operations:view`）：
- 平台管理员可在 JeecgBoot 权限管理界面配置菜单/按钮权限，或参考同目录下的 [syspermission.sql](syspermission.sql) 脚本分配给测试角色；
- 用户只有在拥有相应权限时，SSO 校验接口才会签发页面访问 Token。
