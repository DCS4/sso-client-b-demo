# 宿主系统（Portal 端）SSO 改造上下文参考说明

本目录保存了原系统（如 JeecgBoot 宿主主工程）在接入 SSO 服务端时，对存量代码进行改造的**完整类与完整函数实现**。

## 目录清单

1. **`LoginControllerContext.java`**
   - **`login(...)`**：在本地用户身份认证与 JWT 生成成功后，无缝挂载 `attachSsoSession()`，将 SSO 会话 Cookie 写入响应；
   - **`logout(...)`**：登出时先通过 `portalSsoInnerClient.logout(sid)` 申请全局退出重定向，返回给前端实现跨业务系统单点登出；
   - **`attachSsoSession(...)`**：会话挂载私有核心实现（带降级与容错，SSO 故障不影响本地登录）；
   - **`readCookie(...)`**：Cookie 解析工具函数。

2. **`SysUserControllerContext.java`**
   - **`delete(...)`** 与 **`deleteBatch(...)`**：用户物理删除时联动清理 SSO 全局会话；
   - **`changeStatus(...)`**：用户被冻结时，调用 `ssoUserStatusCoordinator.updateFrozenUsers`，实时将该用户在所有已接入系统的登录态统一注销。

3. **`SysLoginModelContext.java`**
   - 登录入参 DTO 扩展：增加了 `pending` 字段，用于接收客户端重定向跳转登录时携带的一次性凭证标识。

## 接入要点

- 所有注入的 SSO 组件（`PortalSsoInnerClient`、`PortalSsoMapService`、`SsoUserStatusCoordinator`）均配置为 `@Autowired(required = false)`；
- 当部署环境未启动 SSO 服务或相关配置关闭时，原系统自动降级为独立运行，绝不抛出 Bean 缺失或空指针异常。
