# V2 验证说明

本分支将已合并的 V1 Demo 整体替换为 SSO V2 固定回调和页面级 Token 方案，不承诺旧协议兼容。

## 代码检查范围

- 项目中不应再出现 `/sso/v1`、HMAC、JWS/JWKS、PKCE 或 Back-Channel Logout 运行代码；
- 浏览器响应和 Vue 代码中不包含 client_secret、AccessToken、RefreshToken；
- 三个页面路径都从后端 `PageCatalog` 映射到 page_code；
- 固定回调会消费 state、核对 Token 绑定、更新 Session ID，并 303 到白名单目标；
- 每次页面请求调用 checkAccessToken；
- RefreshToken 在同一 Session 锁内轮换并整体替换；
- SSO/权限链路异常时不放行页面；
- 本地所有 POST 仍受 CSRF 保护。

## 部署环境验收

本仓库构建成功不等于真实链路已经联调。环境具备后必须验证：

1. Portal 登录与 pending 恢复；
2. callback、client_id、client_secret 和 auth_mode 配置一致；
3. PAGE_01 Token 不能访问 PAGE_02；
4. 页面权限取消后下一次请求立即失败；
5. RefreshToken 正常轮换和旧值重放拒绝；
6. 页面、客户端、用户停用；
7. Portal 全局退出后旧页面 Token 失效；
8. 多实例 Session/Token 共享和并发刷新；
9. Nginx 不公开 `/internal/**`；
10. 日志、浏览器和前端构建产物中不存在 Secret/Token。

本次按项目约定只提交代码与文档，未把无法连接真实 Portal/SSO 的本地运行结果当作端到端验收。
