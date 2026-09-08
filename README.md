# SSO Client B Demo

普通 Spring Boot **2.7.18 / JDK 8** + Vue 3 参考客户端。与 JeecgBoot 无 Java 依赖，仅通过现有 `/sso/v1` HTTP 契约接入。

## 当前实现

- state 一次性消费、S256 PKCE、后端 HMAC 原始请求体签名。
- RS256 身份断言校验、请求 nonce 绑定、可信本地公钥、jti 防重放。
- B 独立 HttpSession、绝对到期限制、独立本地用户表（首次登录最低权限）。
- 普通业务、签名 session status 敏感操作、仅本地退出、全局退出。
- JSON Back-Channel Logout，按 SID 幂等清理全部本地会话，撤销墓碑阻止延迟回调重新建立会话。
- CSRF 保护（仅机器登出回调豁免）、Vue 演示页、H2 与 MySQL SQL。

## 启动

需要 JDK 8、Maven 3.6+；前端 Node 满足 `frontend/package.json` engines。

```sh
cd frontend
npm ci
npm run build
cd ../backend
mvn clean test package
java -jar target/sso-client-b-demo-0.1.0-SNAPSHOT.jar
```

独立开发前端运行 `npm run dev`，后端运行 `mvn spring-boot:run`。Vite 代理 `/api` 到 18080。开发时将 `B_RETURN_URL` 改为前端地址，并在 SSO 登记相同退出地址。

单 JAR 可使用 `bash scripts/package.sh` 或 Windows PowerShell `./scripts/package.ps1`。手动方式：先构建 Vue，再将 `frontend/dist/` 内容复制到 `backend/src/main/resources/static/`，然后 Maven 打包。构建产物不提交 Git。

默认 H2 内存表自动创建，可打开页面；真实登录前必须完成下面配置。重启清空 H2 用户与会话。

| 环境变量 | 内容 |
|---|---|
| SSO_BASE_URL | SSO 公共协议前缀，例如 http://SSO-IP:10089/sso/v1 |
| SSO_ISSUER | 必须与 SSO 的 issuer 完全一致 |
| SSO_CLIENT_ID | demo-client-b（需登记） |
| SSO_CLIENT_SECRET | 与 SSO 解密后的 HMAC Secret 相同 |
| SSO_TRUSTED_JWKS | 可信渠道部署的本地**仅公钥** JWK Set JSON 绝对路径 |
| B_CALLBACK_URL | 浏览器能访问且精确登记的回调地址 |
| B_RETURN_URL | B 首页，亦为精确登记的退出返回地址 |
| COOKIE_SECURE | HTTP 为 false，HTTPS 为 true |

公钥文件格式为 `{"keys":[RSA 公钥 JWK]}`，必须包含 kid、kty、n、e，不得包含私钥。由 SSO 运维通过可信渠道交付。客户端不信任 HTTP 自动下载的新公钥；轮换时先更新可信文件，再切换 SSO 签名密钥。

SQL 见 `sql/`：01 在 B 数据库执行，02 是 SSO 客户端登记模板。MySQL 模式运行时加 `--spring.profiles.active=mysql`，设置 B_DB_URL、B_DB_USER、B_DB_PASSWORD；不会自动修改 MySQL 表。

## 五分钟验证

1. 未登录打开 B，点击统一登录，Portal 登录后返回 B。
2. 普通业务成功，敏感业务通过 SSO 签名状态检查。
3. 仅退出 B，再统一登录：有效 SSO 会话下免密返回。
4. 同一代码部署第二个独立客户端 C，配置不同 client_id、Secret、端口及 Cookie 名；分别登记。
5. B 全局退出，C 下一次请求应返回未登录。页面需刷新，不依赖 WebSocket 推送。
6. Portal 管理员禁用用户，验证 B/C 收到通知失效。
7. 停止 SSO，敏感操作失败；全局退出失败时 B 本地仍退出，界面明确提示失败。

## 范围与限制

这是单实例基础示例，不是完整生产客户端 SDK。内存会话、重放记录和撤销墓碑重启丢失，不支持多副本共享；生产化需共享存储并验证故障恢复。当前没有自动熔断、消息补偿队列或完整浏览器 E2E 测试。请求设 2 秒连接/读取超时，不自动重试一次性兑换，不自动循环跳转。

本地用户 role 演示默认权限归 B 所有；当前业务接口仅要求登录，没有管理员管理页面。每次请求检查本地 enabled。

HTTP 沿用现有内网 V1 环境，签名不提供机密性。Spring Boot 2.7 是历史版本，按本项目 Java 8 兼容要求固定，正式部署需单独管理维护补丁。

端到端依赖 Portal 前端正确处理 ssoRedirect / ssoLogoutRedirect、SSO 配置与真实网络；单元测试通过不能替代这些联调。
