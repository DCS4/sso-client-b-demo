# 普通单点登录与页面受控

两种模式都接入同一个远端 SSO V2，都通过 B 后端回调建立本地会话。区别是：SSO 只确认“你是谁”，还是同时确认“你能否进入这个页面”。

## 1. 普通单点登录 SSO_ONLY

适合已经有自身权限体系的业务系统。SSO 负责统一身份，B 系统根据自己的角色、数据范围和业务规则决定用户能做什么。

本示例中，第一次访问订单页会完成授权并保存一组**系统级 Token**；再访问报表页时复用这组 Token。B 后端每次仍调用 `checkAccessToken`，但不发送 pageCode，不要求 SSO 为每个页面分配权限。

“非页面受控”不等于“匿名访问”或“不再校验”。用户仍须通过认证；示例的普通 HTML 页面只演示身份接入，没有实现接入方真实的订单、报表或数据权限。

## 2. 页面受控 PAGE_CONTROLLED

适合由 SSO 平台统一管理外部系统页面权限的场景。B 后端将真实页面路径映射为 page_code，SSO 根据客户端、页面、用户及相关权限决定是否允许访问。

本示例中，订单页使用 `B_PAGE_01`，报表页使用 `B_PAGE_02`。第一次访问每个页面时会分别申请与其绑定的 Token；以后再次访问该页面时复用已有 Token，并实时向 SSO 校验。

页面许可通过后，业务系统仍可能需要校验订单所属组织、数据范围、按钮操作等规则。统一页面权限不能自动覆盖所有业务权限。

## 3. 逐项对比

| 项目 | SSO_ONLY | PAGE_CONTROLLED |
| --- | --- | --- |
| 客户端凭据 | 一组 client_id / client_secret | 一组 client_id / client_secret |
| 回调地址 | 固定 `/api/auth/callback` | 相同的固定回调 |
| authorize 的 page_code | 省略 | 当前页面编码 |
| checkAccessToken 的 pageCode | 省略 | 当前页面编码 |
| 返回 Token 的页面绑定 | 预期 page_code 为 null | 预期等于所请求页面 |
| 本地存储键 | `__SSO_ONLY__`，会话共用 | 页面编码，各页独立 |
| 打开已授权的另一个页面 | 校验共享 Token | 校验那个页面自己的 Token |
| 打开还未授权的另一个页面 | 共享 Token 有效时无需再授权 | 为新页面发起授权 |
| 管理端页面登记 | 本示例不要求 | 需要登记页面、路径及权限 |
| 页面是否可直接绕过后端 | 不可 | 不可 |
| 本示例的实时远端校验 | 每次进入页面执行 | 每次进入页面执行 |
| 刷新、一次性 state、本地 POST 的 CSRF | 相同公共流程 | 相同公共流程 |

`PageCatalog` 在两种模式下都存在，保存本地路由及回跳目标。SSO_ONLY 中它的 code 只用于本地演示映射，不会成为发给 SSO 的页面权限参数。

## 4. 用一次操作理解区别

假设从全新的 B 会话开始，Token 都处于有效状态：

1. 进入订单页：两种模式都先到 SSO 授权，通过固定回调返回。
2. 再进入报表页：SSO_ONLY 校验共享 Token；PAGE_CONTROLLED 要为报表页单独授权。
3. 返回订单页：两种模式都调用 SSO 校验；正常情况下不重新签发订单页可用的 Token。
4. 回到首页：SSO_ONLY 通常显示本地 Token 数为 1；两个页面都成功授权的 PAGE_CONTROLLED 通常显示为 2。

这里展示的是没有过期、退出或授权撤销等状态变化时的正常路径。Token 数表示本地保存数量，不是用户拥有的全部权限数，也不证明远端 Token 此刻仍有效。

## 5. 退出的区别

- **退出某个页面授权**：PAGE_CONTROLLED 清理该页 Token；SSO_ONLY 中所有页面共用同一个键，所以该接口会清理共享系统 Token，影响所有页面的后续访问。
- **退出 B 系统**：逐个撤销 B 保存的 Token family，并销毁本地 Session；共享模式通常只需处理一组。
- **Portal 全局退出**：由远端 Portal/SSO 负责；B 在后续页面校验中发现授权失效。本地退出接口不代表 Portal 全局退出。

单页退出不清除本地用户摘要，因此 `/api/auth/me` 仍可能返回用户，但 Token 数减少。首页展示不能代替页面守卫。

## 6. 配置切换步骤

1. 让 SSO 管理员确认或修改该客户端的认证模式。
2. 修改 `.env` 中的 `SSO_AUTH_MODE=SSO_ONLY` 或 `SSO_AUTH_MODE=PAGE_CONTROLLED`。
3. 页面受控模式补齐页面登记和用户授权。
4. 重启 B；浏览器使用新的会话或清理原 B 会话，重新从业务页开始。

只改 Vue 文案、菜单或本地配置中的任一项，都不能替代对应的 SSO 登记。一个 client_id 的两端模式必须一致；不要让它同时服务两个模式不同的实例。

## 7. 代码差异在哪里

- `config/SsoConfig.isPageControlled()` 判断模式，`tokenKey()` 决定共享或分页面保存。
- `sso/SsoClient.authorizeUrl()`、`checkAccessToken()` 决定是否发送页面参数。
- `auth/PageAccessService.expectedPageCode()` 决定返回的 Token 应绑定哪个页面。
- `demo/PageView` 和 Vue 仅按模式解释当前行为，不决定授权结果。

其余登录、固定回调、刷新和本地退出使用同一套代码。没有为两种模式增加两套 Controller 或两套 Token 流程。

下一步分别阅读 [SSO_ONLY 教程](tutorials/sso-only.md)和 [PAGE_CONTROLLED 教程](tutorials/page-controlled.md)。
