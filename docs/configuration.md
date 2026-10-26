# 配置说明

## 1. 配置放在哪里

后端只保留 `backend/src/main/resources/application.yml` 一份默认配置。当前环境的地址和凭据填写在根目录 `.env`；从 `.env.example` 复制后编辑即可。真实 `.env` 已被 Git 忽略，不需要改动前端代码来配置 SSO。

后端通过 `spring.config.import` 读取 `.env`，相对的是**进程工作目录**：

| 从哪里启动 | 使用哪份文件 |
| --- | --- |
| 仓库根目录执行 `java -jar ...` | 根目录 `.env` |
| `backend/` 内执行 Maven | `../.env`，仍是仓库根目录那一份 |
| 部署目录执行 `java -jar app.jar` | 部署目录 `.env` |

不要同时在根目录和 `backend/` 放两份 `.env`。修改后重启后端；前端不会读取其中的 client_secret。

`.env` 按 Java properties 格式解析，写 `KEY=value`，不写 `export`，不额外包引号。也可以用进程环境变量注入表中的同名变量。已有环境中的 `sso.base-url` 等 Spring 属性名仍可使用；初次接入建议统一使用模板列出的变量，避免同一项写两种名字。若同时设置了同名进程变量，它优先于文件中的同名变量。

## 2. 配置项

| 变量 | 默认值 | 如何填写 |
| --- | --- | --- |
| `SSO_BASE_URL` | `http://localhost:19089/oauth2Server/oauth2` | 管理员提供的 SSO V2 公共接口前缀，不是 Portal 网页地址 |
| `SSO_CLIENT_ID` | `demo_client_b` | 管理端登记后分配的 client_id，默认值不代表已登记 |
| `SSO_CLIENT_SECRET` | 空 | 管理端交付的真实密钥；模板中的占位值必须替换 |
| `B_CALLBACK_URL` | `http://localhost:18085/api/auth/callback` | 浏览器可访问的完整固定回调，必须与 SSO 登记一致 |
| `SSO_AUTH_MODE` | `PAGE_CONTROLLED` | `PAGE_CONTROLLED` 或 `SSO_ONLY`；与管理端登记保持一致 |
| `SERVER_PORT` | `18085` | B 后端监听端口 |
| `COOKIE_SECURE` | `false` | 本机 HTTP 使用 false；浏览器通过 HTTPS 访问时使用 true |
| `SSO_CONNECT_TIMEOUT` | `2s` | 后端连接 SSO 的超时，支持 `s`、`ms` 等 Duration 单位 |
| `SSO_READ_TIMEOUT` | `3s` | 后端等待 SSO 响应的超时 |

其他固定设置位于 YAML：本地 Session 有效期 30 分钟，Cookie 名称为 `B_DEMO_SESSION`，启用 HttpOnly 和 SameSite=Lax。普通单点登录也使用这些设置。

## 3. 四个地址如何对应

| 地址 | 单 JAR 示例 | Vite 双进程开发示例 |
| --- | --- | --- |
| 浏览器打开的 B 首页 | `http://localhost:18085` | `http://localhost:5173` |
| B 后端监听地址 | `http://localhost:18085` | `http://localhost:18085` |
| SSO 登记的系统地址 sysAddress | `http://localhost:18085` | `http://localhost:5173` |
| SSO 登记的回调路径 callbackAddress | `/api/auth/callback` | `/api/auth/callback` |
| `.env` 的 B_CALLBACK_URL | `http://localhost:18085/api/auth/callback` | `http://localhost:5173/api/auth/callback` |

完整回调 = 系统地址 + 固定回调路径。浏览器需要回到正在访问的那个 B 系统入口；Vite 会把 `/api/auth/callback` 代理到后端。这样回调后的 `/pages/orders` 和“返回首页”也留在同一个浏览器地址下。

如果用 IP 或域名访问，把首页、系统登记和回调统一替换为同一个 Host；不要首页使用 IP、回调却使用 localhost。`localhost` 永远指运行对应浏览器或后端进程的那台机器。

修改 `SERVER_PORT` 时，还要同步修改 `frontend/vite.config.ts` 的 `backendUrl`。单 JAR 的浏览器入口和回调端口通常也需同步修改。开发端口 5173 已设为固定端口，占用时会报错，不会自动换成另一个端口使登记的回调失效。

## 4. 两种模式分别要准备什么

两者都需要 SSO 地址、client_id、client_secret、固定回调和匹配的模式登记。

- `SSO_ONLY` 不需要为了本示例向 SSO 登记页面权限。三个本地页面仍保留演示路由，但它们的 page_code 不会发送给 SSO。
- `PAGE_CONTROLLED` 还需要登记 `PageCatalog` 中的页面、路径和权限编码，并给测试用户分配相应权限。

页面记录及可观察结果见[模式详解](auth-modes.md)和两篇模式教程。

## 5. 常见配置误解

- 后端成功启动不代表客户端已经在 SSO 登记成功；访问业务页才会开始协议交互。
- Vue 的 `VITE_*` 变量可能进入浏览器产物；本项目不通过它们保存 SSO 密钥。
- `B_CALLBACK_URL` 是后端处理的固定回调，不是订单页、Portal 登录页或 Vue 路由。
- `SSO_AUTH_MODE` 不能只改 B 而不改 SSO；同一个 client_id 不要同时运行两个不同模式的实例。
- 仓库没有 `application.properties`；若旧构建目录里仍有该文件或旧包名类，先运行 `mvn -f backend/pom.xml clean` 再构建。
