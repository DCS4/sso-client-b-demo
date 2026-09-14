# SSO Server 核心服务端资产

本目录收纳了从一体化平台项目完整迁移的 **SSO 集中认证服务端核心源码与集成资产**。

## 架构组成

```text
sso-server/
├── sso-core/              # SSO 核心服务模块 (包含所有协议端点、RSA密钥轮转、Token/Session、Outbox登出)
├── sso-starter/           # 独立微服务启动器 (Spring Boot 2.7.18 独立运行应用)
└── portal-integration/    # 宿主系统协同层 (Portal 端的内部通信客户端与事件发件箱)
    ├── src/main/java/     # 协同业务代码
    ├── src/main/resources/# 协同数据表 DDL
    └── modified-context/  # 宿主系统核心函数修改上下文与参考范例
```

## 核心能力

1. **标准 OIDC / OAuth2 协议端点**：
   - `/.well-known/jwks.json`：公钥端点（支持自动轮转与白名单校验）；
   - `/authorize`：授权端点（支持静默检测、S256 PKCE、Nonce 绑定）；
   - `/token`：兑换 Token 端点（签发 RS256 ID Token）；
   - `/logout`：双向全局登出端点（Transactional Outbox 保证可靠通知）。
2. **安全防护机制**：
   - 客户端密钥采用加密存储（AES-GCM / PBKDF2）；
   - 敏感操作校验支持 HMAC 请求体签名；
   - 授权码一次性消费与 jti 防重放。
3. **零侵入集成**：
   - 宿主系统（Portal）仅需作为普通 Spring Boot 依赖引入 `portal-integration`，并在登录/登出处挂载钩子，即可秒级接入。

## 数据库脚本

DDL 脚本已统合存放在根目录 `sql/` 下：
- `sql/03-sso-server-schema.sql`：SSO 服务端核心库（客户端登记表、登出发件箱表等）；
- `sql/04-portal-user-status.sql`：门户端用户状态变更事件表。
