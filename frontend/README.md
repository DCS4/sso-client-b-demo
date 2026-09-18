# B Demo 前端

Vue 只展示本地登录状态和三个普通页面链接，不持有 SSO 的 client_secret、AccessToken 或 RefreshToken。

开发启动：

```bash
npm ci
npm run dev
```

Vite 会把 `/api/**` 和 `/pages/**` 转发给 `http://localhost:18080`。`/pages/**` 必须到达 B 后端页面守卫，不能配置为前端静态 fallback。

生产构建：

```bash
npm run build
```

根目录打包脚本会将 `dist/` 复制到 Spring Boot 静态资源目录。完整接入说明见 `../docs/integration-guide.md`。
