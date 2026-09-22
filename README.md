# SSO V2 Client B Demo

独立的外部系统参考实现：Spring Boot **2.7.18 / JDK 8** + Vue 3。项目只通过 HTTP 接入 DCS4/yth 的 SSO V2，不依赖 JeecgBoot Java 类。

> V2 分支：`codex/sso-v2-client-demo`
>
> 服务端分支：`DCS4/yth feat/sso-all-in-one`（待合并修复：`codex/sso-v2-config-hardening-20260922`）

服务端接口文档：`DCS4/yth/docs/SSO-V2接口文档.md`。当前联调版本将业务接口响应外壳统一为 JeecgBoot 的 `success/message/code/result`；外部客户端只解析 `result`，并且必须同时检查 `success=true` 和校验数据 `result.active=true`，不能继续按照旧版 `code/msg/data` 处理。

## 演示内容

- 一个外部系统只使用一组 `client_id + client_secret`；
- 所有页面共用 `/api/auth/callback` 固定后端回调；
- 三个普通页面分别映射 `B_PAGE_01`、`B_PAGE_02`、`B_PAGE_03`；
- 每次直接进入页面都由 B 后端调用 `checkAccessToken(accessToken, pageCode)`；
- AccessToken、RefreshToken 和 client_secret 全部留在 B 后端；
- AccessToken 失效后串行轮换 RefreshToken，并原子替换 Token 对；
- state 一次性消费并绑定服务端目标页面，不接受浏览器提供的任意回跳 URL；
- 同一代码支持 `PAGE_CONTROLLED` 和 `SSO_ONLY`；
- 页面 Token family 注销与“退出本系统”；不冒充 Portal 全局退出；
- 所有本地 POST 保留 CSRF 保护。

示例没有保留旧版 HMAC、JWS/JWKS、PKCE、多回调或 Back-Channel Logout 代码。

## 仓库分工

- `DCS4/yth` 保存 SSO 服务端实现和权威《SSO V2 对外接口文档》；
- 本仓库保存外部系统可独立运行的代码和面向接入方的详细改造指南；
- 外部厂商不需要把本项目作为依赖，只需参考其中的集中回调、页面守卫和 Token 仓库。

## 运行配置

本 Demo 的默认值同时写在 `backend/src/main/resources/application.properties`（本地/IDE 启动）和 `application.yml`（服务器部署），两份要保持一致；同一份 classpath 里 `.properties` 优先级更高。

`client_secret` 不进版本库：两个文件里都只留 `${SSO_CLIENT_SECRET:}` 占位，真实值放在进程工作目录的 `.env`（复制 `.env.example`，已在 .gitignore 中）。仓库根目录启动读 `.env`，`backend/` 目录启动读 `../.env`。`.env` 里也可以直接写 Spring 属性名（如 `sso.base-url`）来覆盖默认值。

| 环境变量 | 示例 | 说明 |
| --- | --- | --- |
| `SSO_BASE_URL` | `http://192.9.230.21:10089/oauth2Server/oauth2` | SSO V2 公共前缀 |
| `SSO_CLIENT_ID` | `client_xxx` | 管理端创建客户端后返回 |
| `SSO_CLIENT_SECRET` | `...` | 明文只交付一次，只允许后端保存 |
| `B_CALLBACK_URL` | `http://192.9.230.80:18085/api/auth/callback` | 必须与 SSO 登记值完全一致 |
| `SSO_AUTH_MODE` | `PAGE_CONTROLLED` | 或 `SSO_ONLY`，必须与服务端登记一致 |
| `COOKIE_SECURE` | `false` | HTTP 为 false，HTTPS 为 true |
| `SSO_CONNECT_TIMEOUT` | `2s` | 后端连接超时 |
| `SSO_READ_TIMEOUT` | `3s` | 后端读取超时 |

页面受控模式还需在 SSO 管理端登记：

| page_code | page_path | 示例 permission_code |
| --- | --- | --- |
| `B_PAGE_01` | `/pages/orders` | `b:orders:view` |
| `B_PAGE_02` | `/pages/reports` | `b:reports:view` |
| `B_PAGE_03` | `/pages/operations` | `b:operations:view` |

系统地址登记为浏览器实际访问的 B 基础地址，例如 `http://192.9.230.80:18080`；回调只登记相对路径 `/api/auth/callback`。

## 启动

后端：

```bash
cd backend
mvn spring-boot:run
```

前端开发服务器：

```bash
cd frontend
npm ci
npm run dev
```

Vite 将 `/api` 和 `/pages` 代理到 `http://localhost:18085`。后端同时可以直接提供构建后的前端静态文件；打包脚本会先构建 Vue，再生成单个可执行 JAR。

只改前端时，手动同步静态产物（`backend/src/main/resources/static/` 已在 .gitignore 中，属于本地构建产物）：

```bash
cd frontend && npm run build && cd ..
rm -rf backend/src/main/resources/static
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
```

Windows PowerShell：

```powershell
cd frontend; npm run build; cd ..
Remove-Item -Recurse -Force backend/src/main/resources/static
New-Item -ItemType Directory -Force backend/src/main/resources/static | Out-Null
Copy-Item frontend/dist/* backend/src/main/resources/static -Recurse -Force
```

改完前端后重启后端才会重新读取 `static/`；打成 JAR 时 Maven 会把 `static/` 一起打包，所以必须**先拷文件再执行 `mvn package`**，或直接跑 `scripts/package.*`。

Linux/macOS：

```bash
bash scripts/package.sh
```

Windows：

```powershell
./scripts/package.ps1
```

## 观察流程

1. 打开 B 首页，直接点击“订单查询”。
2. B 后端没有页面 Token，生成 state 并 302 到 SSO。
3. Portal 登录或已有 SSO 会话完成静默授权。
4. SSO 只回调 `/api/auth/callback?code=...&state=...`。
5. B 后端兑换并保存 Token，再以 303 返回 `/pages/orders`。
6. `/pages/orders` 后端调用 `checkAccessToken + B_PAGE_01`，通过后输出页面。
7. 再次点击该页面时不重新签发 Token，但仍实时调用 SSO 校验。
8. 点击另一个页面时，页面受控模式会取得与其 `page_code` 独立绑定的 Token。

浏览器开发者工具中只能看到 code、state 和 B 自己的 HttpOnly Session Cookie，看不到 client_secret、AccessToken 或 RefreshToken。

## 重要代码

| 文件 | 作用 |
| --- | --- |
| `SsoClient.java` | 集中封装五个 SSO 后端接口 |
| `PageAccessService.java` | 页面校验、刷新、固定回调和注销主流程 |
| `LoginStateStore.java` | state 限时、一次性消费及目标绑定 |
| `PageTokenStore.java` | 按本地 Session 与 page_code 保存 Token |
| `PageCatalog.java` | 页面路径与 page_code 的服务端白名单 |
| `PageController.java` | 直接页面 URL 也必须经过后端守卫的示例 |

详细改造步骤见 [外部系统接入指南](docs/integration-guide.md)，逐接口字段见 [V2 协议摘要](docs/protocol.md)。

## 示例边界

这是协议参考实现，不是生产 SDK：

- Token 暂存在单实例 HttpSession；多实例应使用共享加密存储或粘性会话；
- 页面表使用代码白名单，真实系统可改为数据库/配置中心，但不能信任浏览器 target；
- 未实现自动熔断、指标、审计落库和浏览器 E2E；
- 第一版没有 Back-Channel Logout，Portal 退出后在下一次页面校验时发现 SID 已失效；
- Spring Boot 2.7 已停止开源维护，示例仅因 JDK 8 兼容要求固定此版本。
