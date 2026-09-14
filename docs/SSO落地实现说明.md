# SSO 落地实现说明

> 本文以当前仓库代码为唯一依据，说明已经落地的实现、运行开关和部署前置条件。它不是预研或目标设计文档；未在本文列出的能力，均不应视为已经交付。

## 1. 模块与部署边界

| 组件 | 代码位置 | 职责 |
| --- | --- | --- |
| SSO 领域模块 | `sac-module-sso` | 会话、授权码、HMAC、JWS、JWKS、登出 Outbox、内部回环接口 |
| SSO 启动模块 | `jeecg-server-cloud/sac-cloud-sso-start` | 启动 SSO Spring Boot 服务 |
| System/门户改造 | `jeecg-module-system/jeecg-system-biz` | 原登录/登出后的可选 SSO 钩子、用户状态 Outbox |

SSO 服务模板监听 `127.0.0.1:19089`。Nginx 应将外部 `192.9.230.21:10089/sso/v1/**` 代理至该回环地址；`/internal/**` 不得经 Nginx 暴露。

System、门户与 SSO 位于同一台主机时，内部调用固定为：

```text
http://127.0.0.1:19089/internal/sso/**
X-INNER-TOKEN: <同一随机密钥>
```

实现中没有 `29089`、跨机 System 通道或 System→SSO HMAC 通道。其它业务系统 B 可位于不同服务器，但只能调用对外的 `/sso/v1/**` 协议接口。

## 2. System 的总开关与兼容模式

System 侧只有一个总开关：

```yaml
sso:
  integration:
    enabled: false
```

默认值为 `false`。未部署 SSO、或只需运行原 System 时，不配置该项即可。

关闭时，`PortalSsoInnerClient`、`PortalSsoMapService`、`UserStatusOutboxService` 和 `SsoUserStatusCoordinator` 都不会注册为 Spring Bean：

- 原登录、登出不会建立回环连接，不会设置 `SSO_SID` Cookie；
- 不会读写 `portal:sso-map:*`，不访问 `user_status_event` 表，也不启动其投递任务；
- 用户单删、批删、冻结沿用原 Controller 到原 Service 的调用及事务边界；
- `SysLoginModel.pending` 仅是被动接收字段，关闭时不参与任何逻辑。

启用 SSO 时，System 的 Nacos/受控配置至少应包含：

```yaml
sso:
  integration:
    enabled: true
  portal:
    inner-url: http://127.0.0.1:19089
    public-base-url: http://192.9.230.21:10089
    inner-token: ${SSO_INNER_TOKEN}
  system:
    inner-url: http://127.0.0.1:19089
    inner-token: ${SSO_INNER_TOKEN}
    outbox-fixed-delay-ms: 10000
```

门户回环调用与 System Outbox 投递的连接、读取超时均为 2 秒。门户钩子发生异常时只记录日志，原本地登录或登出仍会继续。

## 3. SSO 服务配置

`jeecg-server-cloud/sac-cloud-sso-start/src/main/resources/application.yml` 是默认加载的部署/Nacos 配置模板，不应提交真实密钥。关键配置如下：

```yaml
server:
  address: 127.0.0.1
  port: 19089

sso:
  public-base-url: http://192.9.230.21:10089
  portal-login-url: http://192.9.230.21:10088/login
  portal-post-logout-url: http://192.9.230.21:10088/login
  issuer: http://192.9.230.21:10089
  inner-token: ${SSO_INNER_TOKEN}
  master-key-env: SSO_MASTER_KEY
  key-store-path: /opt/jeecg-sso/keys
  current-kid: "2026-01"
  # 可在切换前预发布新 kid，或保留旧 kid 至明确截止时间；过期后不会再出现在 JWKS。
  jwks-additional-kids: {}
  revocation-tombstone-ttl: 24h
  revocation-recovery-fixed-delay-ms: 10000
  backchannel-allowed-cidrs:
    - 192.9.230.0/24
```

`redirect_uri`、`post_logout_redirect_uri` 和 `backchannel_logout_uri` 都须与登记值精确相等，并只允许登记 transport 对应的 `http`/`https`、字面 IPv4 地址和 `backchannel-allowed-cidrs` 中的企业网段。允许网段必须是 `/24` 或更精确，拒绝用整个私网或全网作出站白名单；即使 CIDR 被误配为过宽网段，也拒绝 IANA 特殊用途的共享地址、环回、链路本地/云元数据、IETF 协议保留、文档 TEST-NET、基准测试、组播和保留段；RFC1918 私网仍需在企业 CIDR 中显式允许。未配置允许 CIDR 时，SSO 拒绝回跳或发起任何 Backchannel 请求。

`SSO_MASTER_KEY` 是 Base64 编码的 32 字节 AES 主密钥，用于解密数据库中保存的客户端 HMAC 密钥。签名密钥保存在 `key-store-path`；若不存在 `current-kid.jwk`，服务首次启动会生成 2048 位 RSA 私钥。JWKS 只发布当前 `current-kid`，以及 `jwks-additional-kids` 显式列出且未过截止时间的 kid；目录内任何其它 `.jwk` 都不会自动发布。POSIX 主机上目录必须为 `0700`、JWK 必须为 `0600`，权限不符会拒绝启动；Windows 部署须以等效 ACL 仅授权运行账户。

密钥轮换的受控配置示例（时间须使用 UTC ISO-8601）：

```yaml
sso:
  # 切换前预发布新公钥；旧 key 保留到所有旧令牌、客户端缓存都失效之后。
  jwks-additional-kids:
    "2026-01": "2026-06-30T00:00:00Z"
    "2026-06": "2026-06-30T00:00:00Z"
  # 客户端白名单确认后，再改为新签名 key 并重启/滚动发布 SSO。
  current-kid: "2026-06"
```

截止时间一到，旧 kid 会自动从 JWKS 移除；私钥 JWK 文件再由受控运维清理。配置了尚未到期的额外 kid 但文件不存在时，SSO 会拒绝启动，避免轮换配置与实际密钥材料不一致。

## 4. 数据库对象

| 数据库 | 脚本 | 表 |
| --- | --- | --- |
| SSO 库 | `sac-module-sso/src/main/resources/db/sso-schema.sql` | `sso_client`、`sso_client_redirect_uri`、`sso_logout_event` |
| System 库 | `jeecg-module-system/jeecg-system-biz/src/main/resources/db/sso-user-status-event.sql` | `user_status_event` |

已有 SSO 库须额外执行 `sac-module-sso/src/main/resources/db/sso-outbox-claim-migration.sql`；已有 System 库须额外执行 `jeecg-module-system/jeecg-system-biz/src/main/resources/db/sso-user-status-event-claim-migration.sql`。两份迁移为 Outbox 增加实例抢占租约字段，新库只执行对应最新版基础脚本即可。

### 4.1 升级顺序

1. 备份 SSO 库与 System 库；对已建表的环境分别执行两份 claim migration。迁移是一次性 `ALTER TABLE`，不可重复执行。
2. 首次升级含“全局撤销恢复索引”的版本时，应先停止旧版 SSO 实例，避免旧代码在回填完成标记写入后继续产生未进入全局索引的墓碑；再发布新版 SSO。新版首次启动会回填仍在 TTL 内的旧索引。
3. 确认 `/sso/v1/health/readiness` 为 `UP`，并确认 `sso_logout_event` 已有 `claim_token`、`claim_until` 字段。
4. 再发布 System/门户代码。只有准备好 `SSO_INNER_TOKEN`、Nginx 与客户端联调时，才在 System 配置启用 `sso.integration.enabled=true`。
5. 对新建环境只执行两份最新版基础建表脚本，**不要**再执行 claim migration。

`sso_client.client_hmac_key_cipher` 保存 AES-GCM 密文，格式为 `base64(iv):base64(ciphertext)`。当前代码没有客户端管理后台；上线前须通过受控初始化流程登记 `client_id`、精确回调地址、登出地址和密文 HMAC 密钥。

## 5. 门户登录与登出

### 5.1 登录

`LoginController.login()` 先完整执行原验证码、账号状态、密码校验、JWT 签发和用户信息组装。仅当 `sso.integration.enabled=true` 且本地登录成功后，追加以下步骤：

1. 从请求头 `X-Access-Token` 查询旧 token 的服务端 `token SHA-256 → sid` 映射；映射不存在时读取同一浏览器的 HttpOnly `PORTAL_SSO_REF` 引用 Cookie；不信任请求体中的旧 SID。
2. 回环调用 `POST /internal/sso/beginSession`，传递 `sub=sys_user.id`、`username`、`old_sid` 和可选 `pending`。
3. SSO 对同一 `sub` 的旧会话执行吊销后，建立新的 Redis 全局会话。
4. 门户响应添加两枚 HttpOnly Cookie：

   ```text
   Set-Cookie: SSO_SID=<sid>; Path=/sso/v1; Max-Age=<SSO剩余秒>; HttpOnly; SameSite=Lax
   Set-Cookie: PORTAL_SSO_REF=<sid>; Path=/; Max-Age=<SSO剩余秒>; HttpOnly; SameSite=Lax
   ```

`PORTAL_SSO_REF` 仅供门户服务端取得同浏览器的旧 SID；SSO 在吊销前仍检查其归属 `sub`，因此伪造或跨用户引用不能注销其他用户会话。

5. 门户将新 JWT 的 SHA-256 哈希映射至 SID，TTL 取本地 JWT 剩余时间和 SSO 会话剩余时间的较小值。
6. 如果 `pending` 对应有效 B 系统授权请求，登录响应的 `result` 追加 `ssoRedirect`。

前端在收到 `ssoRedirect` 时应执行 `location.href = ssoRedirect`；登录页若带 `_sso` 查询参数，应在登录请求体中回传为 `pending`。

### 5.2 登出

`LoginController.logout()` 在原本地 Token、Redis、Shiro 清理前，尝试：

1. 根据当前 token 查 SID；
2. 回环调用 `POST /internal/sso/logout`；
3. SSO 吊销全局会话、写客户端登出 Outbox，并返回一次性 `logout_request`；
4. 门户响应的 `result` 追加 `ssoLogoutRedirect`。

前端收到该字段后必须跳转。浏览器访问 `GET /sso/v1/logout?request=...` 后，仅当请求中的 Cookie SID 与一次性请求 SID 相等时，SSO 才清除路径为 `/sso/v1` 的 Cookie，并 302 回门户登录页。回环失败不会阻断本地登出。

## 6. 对外 SSO 协议

| 接口 | 调用方 | 实现行为 |
| --- | --- | --- |
| `GET /sso/v1/authorize` | 浏览器/B | 精确校验客户端、回调地址和企业网段；要求 S256 PKCE；全局会话有效则签发 90 秒授权码，否则保存 10 分钟 pending 并跳转门户登录页 |
| `POST /sso/v1/exchange` | B 后端 | 校验 HMAC、一次性消费授权码、校验 client/redirect/PKCE/会话，登记 `session-clients`，签发 60 秒 `identity_assertion` |
| `POST /sso/v1/logout` | B 后端 | 校验 HMAC、回跳地址和该客户端是否已登记进会话，吊销会话并返回浏览器清 Cookie 的一次性请求 |
| `GET /sso/v1/logout` | 浏览器 | 原子消费 `logout_request`；仅 payload SID 与浏览器 Cookie SID 相等时清除 SSO Cookie，再回跳已登记地址 |
| `GET /sso/v1/jwks.json` | B 后端 | 发布当前 kid 与配置明确允许、尚未到截止时间的额外 kid；过期 kid 自动停止发布 |
| `GET /sso/v1/health/readiness` | B 后端 | Redis PING 与当前公钥可用时返回 `UP` |
| `POST /sso/v1/session/status` | B 后端 | 经 HMAC 校验后返回含 `client_id/sid/request_nonce/iat/exp/active` 的 RS256 JWS；仅已登记该 SID 的客户端可获 `active=true` |

外部 HMAC 请求使用以下请求头：

```text
X-SSO-Client
X-SSO-Timestamp
X-SSO-Nonce
X-SSO-Signature
```

待签名串为：

```text
METHOD + "\n" + URI + "\n" + client_id + "\n" + timestamp + "\n" + nonce + "\n" + SHA256(raw_body_hex)
```

服务校验时间窗 ±120 秒，并在**验签成功后**用 Redis `SETNX` 保证 nonce 一次有效。`identity_assertion`、`logout_token` 和敏感操作的 `session_status` 均为带 `kid` 的 RS256 JWS；B 端必须验证签名、固定 `RS256` 算法、`iss`、`aud`、过期时间及各自的业务绑定字段。

## 7. 内部接口与用户状态回收

内部接口都由回环地址和 `X-INNER-TOKEN` 双重限制：

| 接口 | 调用方 | 行为 |
| --- | --- | --- |
| `POST /internal/sso/beginSession` | 门户 | 建立会话、处理旧会话和 pending 回跳 |
| `POST /internal/sso/logout` | 门户 | 吊销 SID 并生成浏览器登出请求 |
| `POST /internal/sso/revokeUserSessions` | System Outbox | 接收 `USER_DISABLED`/`USER_DELETED`/`USER_ENABLED`；禁用先写 Redis 门禁并快照 SID，再逐个吊销和登记客户端通知 |

启用开关后，System 的 `/sys/user/delete`、`/deleteBatch`、`/frozenBatch` 通过 `SsoUserStatusCoordinator` 执行。用户数据变更与 `user_status_event` 写入同一事务；任务每 10 秒扫描待投递事件，以 1 分钟、5 分钟、30 分钟退避回环调用 SSO。禁用/删除事件会持续保留并重试，直至 SSO 接收成功；解冻会投递 `USER_ENABLED`，移除 SSO 建会话门禁。同一 `sub` 存在较早的未投递事件时，后续事件会等待，避免“解冻”越过重试中的“禁用”。每次投递先用数据库条件更新取得 30 秒租约；只有持有租约的实例能写回结果，租约超时后才允许另一实例补投。

`/userQuitAgent` 未接入全局会话回收：该入口变更的是指定租户下的离职关系，不等同于全局 `sys_user` 禁用或删除。

## 8. 客户端登出通知与会话

SSO 会话保存在 Redis：绝对有效期 4 小时、空闲有效期 30 分钟。会话关联的 B 客户端以 `sso:session-clients:{sid}` 维护，并与会话绝对过期时间对齐。

吊销时，SSO 通过 Lua 原子写入撤销墓碑、快照客户端集合、删除有效会话，并将尚未完整落库通知的 SID 保存在用户级和全局 subject 恢复索引中。MySQL Outbox 任一插入失败时，SSO 自身每 10 秒扫描该索引，按相同 SID/客户端幂等补写事件；不依赖门户或 System 的原始请求再来一次。所有事件持久化成功后才原子清理墓碑和索引。升级自全局 subject 索引出现前的版本时，SSO 首次启动会以 Redis `SCAN` 回填旧的 `sso:user-revocations:{sub}` 索引；仅完整扫描成功后才写完成标记，失败会每分钟重试。

会话读取、空闲刷新、客户端登记也都在 Lua 中完成，不能在注销删除后用旧 Java 对象重新写回会话。禁用门禁与创建会话共享 Redis 原子边界，因此已经通过本地账号校验但尚未创建 SSO 会话的并发登录会被拒绝。

投递请求为：

```json
{"logout_token":"<JWS>"}
```

投递超时为 2 秒，失败按 1 分钟、5 分钟、30 分钟退避；最多 5 次重试后状态标记为失败。每次投递用相同 `jti` 重签发短期 Logout Token，避免后续退避收到过期 Token。每个 SSO Outbox 事件也先取得 30 秒数据库租约，避免多实例重复投递或迟到实例覆盖成功状态；租约进程异常到期后以相同 `jti` 重投。客户端必须按 `sid` 清理本地会话，并以 `jti` 做幂等处理。

## 9. 当前未在本仓库交付的部分

- Nginx 的 `10088`、`10089` 实际代理配置；
- B 系统的登录过滤器、PKCE/state 保存、HMAC 调用、JWS/JWKS 校验和本地会话逻辑；
- 门户前端对 `pending`、`ssoRedirect`、`ssoLogoutRedirect` 的两处跳转处理；
- 客户端登记、HMAC 密钥加密和密钥轮换的管理界面或运维命令（JWK 文件物理清理由受控手工步骤完成）；
- 连接实际 Nacos、MySQL、Redis 与 B 系统的端到端联调。

## 10. 已执行验证

已通过 Java 编译与 SSO 单元测试（14 个常规测试）：

```text
mvn -pl jeecg-module-system/jeecg-system-biz -DskipTests compile
mvn -pl sac-module-sso -DskipTests compile
mvn -PSpringCloud -pl :sac-cloud-sso-start -am -DskipTests compile
mvn -pl sac-module-sso -DskipTests=false test
```

测试覆盖禁用门禁、撤销 Outbox 墓碑及恢复扫描、HMAC nonce 顺序、Backchannel CIDR/特殊地址规则、JWKS 额外 kid 的保留期限，以及批量投递中每一条 Outbox 都从实际抢占时刻开始计算租约。`SessionServiceRedisIntegrationTest` 在显式提供隔离 Redis DB 时验证真实 Lua 与旧索引回填：必须同时设置 `SSO_TEST_REDIS_HOST`、非零 DB 编号 `SSO_TEST_REDIS_DB`（仅 `1`–`15`）和确认值 `SSO_TEST_REDIS_CONFIRM=I_UNDERSTAND_REDIS_TEST_WRITES`，可选设置 `SSO_TEST_REDIS_PORT`。测试绝不执行 `FLUSHDB`，只清理自身随机 subject/SID 所写的键；旧索引迁移用例还会先检查 `DBSIZE == 0`，不为空即安全跳过，避免扫描或修改既有数据。若测试开始前已存在升级完成标记，回填用例同样跳过。非零 DB 要求也使 Redis Cluster 无法误跑此测试。`LogoutOutboxClaimMySqlIntegrationTest` 在显式提供一次性 MySQL schema 时仅验证 InnoDB 条件更新的并发语义（不替代 MyBatis-Plus Mapper 集成验证）：设置 `SSO_TEST_MYSQL_JDBC_URL`，可选设置 `SSO_TEST_MYSQL_USER`、`SSO_TEST_MYSQL_PASSWORD`。该测试只创建和删除随机命名的 `sso_test_claim_*` 表，仍只能指向一次性测试 schema。两项外部集成测试尚需在一次性环境实际执行并留存结果；它们不覆盖 Nacos、Nginx 和 B 系统端到端联调。
