# SSO 接口清单（通俗详解版）

> 依据当前分支实际代码：`sac-module-sso` 的 `SsoProtocolController`（`/sso/v1/**`）、`InnerSsoController`（`/internal/sso/**`），以及 `jeecg-system-biz` 的登录/登出钩子与两个 Outbox。
>
> 阅读建议：先看第 0 节「全景图」和第 1 节「名词/时效速查」，再看第 5 节「场景走读」把整条链路串起来，最后按第 2/3/4 节逐接口细看。

---

## 0. 全景图：4 个角色 + 2 道门

### 0.1 四个角色

| 角色 | 是谁 | 在哪 |
|---|---|---|
| 浏览器 | 用户操作的地方，负责带 Cookie、走 302 跳转 | 用户电脑 |
| 门户（System） | 本项目的 JeecgBoot System，管账号密码登录、用户管理 | `192.9.230.21:10088` |
| SSO 服务 | 本次新增的独立模块 `sac-module-sso`，管「全局会话」——即「这个人在这台机器上到底登没登录」的权威 | 公网口 `192.9.230.21:10089`（Nginx 代理）；回环口 `127.0.0.1:19089`（仅同机） |
| B 系统 | 接入 SSO 的其他业务系统（注册为 `sso_client` 的客户端） | 各自部署 |

### 0.2 两道门（接口分组的本质）

```
                    ┌─────────────────────────────────────────────┐
   浏览器/B后端 ───▶ │ 门A：Nginx 10089                            │──▶ /sso/v1/**（对外协议，7 个接口）
                    │   只转发 /sso/v1/**，其余路径一律 404         │
                    └─────────────────────────────────────────────┘

   门户/System ────▶ ┌─────────────────────────────────────────────┐──▶ /internal/sso/**（内部回环，3 个接口）
  （同机进程）       │ 门B：127.0.0.1:19089                        │
   带 X-INNER-TOKEN │   物理上只有同一台机器能碰到 + token 校验      │
                    └─────────────────────────────────────────────┘
```

- **门 A** 是给浏览器和 B 系统后端的：走公开地址，靠 **HMAC 签名**（B 后端）或 **Cookie**（浏览器）识别身份。
- **门 B** 是给「门户 System 进程」的：设计上冻结为「固定同机回环」，不走 Feign/Nacos，Nginx 对 `/internal/**` 一律 404，外部网络根本碰不到。

### 0.3 总开关

`sso.integration.enabled`（默认 `false`）。关闭时：门户侧的 `PortalSsoInnerClient`、`PortalSsoMapService`、`UserStatusOutboxService`、`SsoUserStatusCoordinator` 这四个 Bean 根本不注册，System 保持原登录/登出/用户管理路径，**一行原逻辑不改**。

---

## 1. 名词与时效速查

### 1.1 时效一览表（`SsoProperties` 默认值）

| 名词 | 通俗理解 | 时效 |
|---|---|---|
| **全局会话 sid** | SSO 发的「总门票」，代表"这个人已登录门户"。存 Redis，4 小时绝对过期 + 30 分钟空闲过期，每次校验时自动刷新空闲时间 | 4h / 30min |
| **pending** | 「授权参数暂存单」。用户没登录时，B 系统的授权请求（client_id、回调地址、PKCE 挑战值等）先存这里，等门户登录成功后再取出来用 | 10min |
| **code（授权码）** | 「一次性临时票据」。登录后 SSO 签发，浏览器带给 B 系统后端，**只能用一次**，90 秒过期 | 90s |
| **identity_assertion** | 「带签名的身份声明」（RS256 JWT）。B 后端用 code 换回来的东西，含 sub/username/sid 等，**60 秒过期**且绑定本次请求的 nonce | 60s |
| **logout_request** | 「清 Cookie 的一次性小票」。后端登出后发给浏览器，浏览器拿它去 SSO 清 `SSO_SID` Cookie，用完即焚 | 30s |
| **logout_token** | 「登出通知令牌」（RS256 JWT）。SSO 主动 POST 给各 B 系统后端，通知"这个 sid 作废了"；按 jti 幂等，每次重投重新签发 | 10min |
| **撤销墓碑** | 「撤销回执」。会话被吊销时在 Redis 留一份含「当时登录过哪些客户端」的快照，保证登出通知不丢、重复吊销幂等 | 24h |
| **nonce** | HMAC 请求的「一次性随机数」，防重放；验签成功才登记 | 5min |
| **时间窗** | HMAC 请求时间戳与服务器时间的最大允许偏差 | ±120s |
| **登录重试上限** | 同一次授权尝试（clientId+state）内「没登录成功就回来」的次数上限 | 3 次 |

### 1.2 Redis Key 速查（`SsoRedisKey`）

| Key | 类型 | 干什么用 |
|---|---|---|
| `sso:session:{sid}` | String(JSON) | 全局会话本体（sub、username、auth_time、lastActiveAt、absoluteExpiresAt） |
| `sso:session-clients:{sid}` | Set | 这个 sid 下**实际登录过**哪些客户端（精确登出/状态查询的依据） |
| `sso:user-sessions:{sub}` | ZSET（score=绝对过期时刻） | 某用户名下所有 sid 的索引，用于「禁用用户→一键吊销其全部会话」 |
| `sso:subject-disabled:{sub}` | String | **建会话门禁**：存在即该用户被禁用，`create()` 与 `requireActive()` 的 Lua 脚本在同一原子边界检查它 |
| `sso:user-revocations:{sub}` | Set | 该用户「已撤销但登出通知还没落库完」的 sid，供恢复任务补齐 |
| `sso:revocation-subjects` | Set | 含待恢复墓碑的 subject 集合，让恢复任务不用扫描 Redis keyspace |
| `sso:revocation:{sid}` | String(墓碑) | 撤销幂等 + 客户端快照（24h） |
| `sso:code:{code}` | String(JSON) | 一次性授权码（90s，GETDEL 原子消费） |
| `sso:pending:{id}` | String(JSON) | 授权参数暂存单（10min，登录成功后 getAndDelete 取走） |
| `sso:pending-count:{client:sha256(state)}` | Number | 每次授权尝试的失败登录计数（10min） |
| `sso:logout-request:{id}` | String(JSON) | 浏览器清 Cookie 的一次性小票（30s，GETDEL 原子消费） |
| `sso:client-nonce:{client:nonce}` | String('1') | HMAC nonce 一次性登记（SETNX） |
| `portal:sso-map:{sha256(token)}` | String(sid) | **门户侧**（System 的 Redis）：门户 token → sid 的服务端映射，Key 只存 token 哈希 |

> 设计要点：几乎所有「检查+变更」都写成 **Lua 脚本原子执行**（`SessionService` 里有 6 段），杜绝「检查完会话有效、还没登记客户端，中间被吊销」这类竞态。

---

## 2. 对外协议接口（`SsoProtocolController`，7 个，Nginx 10089 暴露）

### 2.1 `GET /sso/v1/authorize` —— 授权入口（浏览器）

**一句话**：B 系统发现用户没登录，把浏览器甩到 SSO；SSO 判断「你登没登录过门户」，登过就发 code 直接送回去，没登就先存单再送去门户登录页。

**参数**：`client_id`、`redirect_uri`、`state`、`code_challenge`、`code_challenge_method`（只接受 `S256`，默认值）。

**调用流程**：

1. **验客户端**：`client_id` 必须已登记且启用；`redirect_uri` 必须与登记值**精确相等**，且通过企业 CIDR/SSRF 校验。任一失败 → `400 invalid_client`。
2. **验协议参数**：`state` 和 S256 `code_challenge` 缺一不可（防 CSRF + 防授权码拦截）→ 否则 `400 invalid_request`。
3. **读 Cookie**：从请求取 `SSO_SID`（只有 `Path=/sso/v1` 的请求会带它），调 `requireActive` 原子检查会话（存活？未禁用？空闲没超时？顺便刷新空闲时间）。
4. **分支 A——有有效会话**：直接 `codeService.issue()` 签发 90s 一次性 code（里面绑死 clientId/redirect_uri/state/challenge/sid/sub），`302` 回 `redirect_uri?code=...&state=...`。
5. **分支 B——没会话**：
   - 用 `clientId + sha256(state)` 做计数键**递增重试计数**（为什么不用 IP？注释写得很明白：不能按 Nginx 回环地址或企业 NAT 地址聚合用户，否则同一出口 IP 的人互相拖累）。计数 ≥3 → `400 too_many_attempts`。
   - `pendingService.save()` 暂存授权参数（10min），`302` 到门户登录页 `http://192.9.230.21:10088/login?_sso=<pendingId>`。用户登录后，门户把 `pendingId` 带回 `beginSession`（见 3.1），SSO 才会真正发 code。

```
浏览器 ──GET /sso/v1/authorize──▶ SSO
                                   │ 验 client / redirect_uri / state / PKCE
                                   │ 读 SSO_SID Cookie
                 ┌─────────────────┴──────────────────┐
           会话有效 ✓                          无会话 ✗
                 │                                │
        发 code(90s)                     计数<3? 存 pending(10min)
                 │                                │
   302 → B回调?code&state             302 → 门户登录页?_sso=xxx
```

**错误码**：`400 invalid_client` / `400 invalid_request` / `400 too_many_attempts`。

---

### 2.2 `POST /sso/v1/exchange` —— 换身份（B 后端）

**一句话**：B 系统后端拿着浏览器转交的 code 来 SSO「兑奖」，SSO 层层验明正身后，发回一张 60 秒有效、带私钥签名的身份声明（assertion）。

**HMAC 验签（所有 B 后端接口共用，`HmacVerifier`）**：

- 请求头四件套：`X-SSO-Client`（client_id）、`X-SSO-Timestamp`（秒级）、`X-SSO-Nonce`（一次性）、`X-SSO-Signature`（Base64URL）。
- 待签名串（规范化字符串）：`方法\nURI\nclientId\n时间戳\nnonce\nSHA256hex(原始body)`，用该客户端登记的 HMAC 密钥做 HmacSHA256。
- 校验顺序：客户端启用 → 时间戳在 ±120s 窗内 → 常量时间比较签名 → **验签成功后才用 SETNX 登记 nonce**（非法签名不消耗合法调用方的 nonce）。nonce 用过 → 拒绝。
- 任一环节失败 → `401 invalid_signature`。

**调用流程**（验签通过后）：

1. **原子消费 code**：Lua `GETDEL` 一把取走并删除（90s 内谁先拿到谁用，天然防重放、防并发双花）。code 不存在（没给过/用过/过期）→ `400 invalid_grant`。
2. **绑定比对**：HMAC 验出的 clientId 必须等于 code 里的 clientId，请求的 `redirect_uri` 必须等于 code 里记录的——防止 A 系统拿到的 code 给 B 系统用。
3. **PKCE 校验**：`SHA256(code_verifier)` 必须等于授权时的 `code_challenge`（常量时间比较）——即使 code 在传输中被截获，没有 verifier 也换不出身份。
4. **会话复核**：`requireActive(code.sid)`，且会话的 sub 与 code 里的 sub 一致（防 code 与会话被分别篡改后拼凑）。失效 → `401 invalid_session`。
5. **登记客户端**：`registerClient`（Lua 原子）把 clientId 写进 `sso:session-clients:{sid}`——这一步是「精确登出」的地基：以后只有真正登录过的客户端才有资格吊销这个会话。
6. **签发 assertion**：RS256 签名 JWT，60s 过期，claims 如下：

| claim | 含义 |
|---|---|
| `iss` | `http://192.9.230.21:10089`（B 端按本地预置的**指纹白名单**验签，JWKS 只作参考） |
| `aud` | clientId（只给该客户端用） |
| `sub` / `username` | 用户标识 / 用户名 |
| `sid` / `auth_time` / `session_expires_at` | 全局会话 id / 登录时刻 / 绝对过期时刻 |
| `request_nonce` | **本次请求的 X-SSO-Nonce**——assertion 与请求绑定，跨请求重放这张 JWT 会被 B 端拒绝 |
| `jti` / `iat` / `exp` | 唯一 id / 签发 / 过期（+60s） |

**为什么 code 和 assertion 要分两段？** code 是给浏览器转交的「取货凭证」，短命且一次性；assertion 是后端之间真正的「身份证据」，带签名、短命、绑 nonce。浏览器全程碰不到任何长期凭证。

---

### 2.3 `POST /sso/v1/logout` —— B 后端发起全局登出

**一句话**：B 系统里用户点了「退出」，B 后端通知 SSO：把这个 sid 的总门票作废旧，SSO 吊销会话、给其他登录过的系统排队发登出通知，并回一张给浏览器清 Cookie 用的一次性小票。

**调用流程**（HMAC 验签同 2.2）：

1. **验绑定**：`post_logout_redirect_uri` 必须与该客户端登记值**完全相等**且过 CIDR 校验（登出回跳地址不允许运行时随意指定，防登出钓鱼）。sid、uri 缺失同样拒绝 → `400 invalid_request`。
2. **验资格**：sid 必须存活，**且该 clientId 已在 `session-clients` 里**——没在这个会话里登录过的客户端无权吊销它 → `403 access_denied`。
3. **吊销 + 通知**（`LogoutService.revokeAndCreateBrowserRequest`）：
   - `SessionService.revoke(sid)`（Lua 原子）：若 `sso:revocation:{sid}` 墓碑已存在直接返回（**幂等**，重复登出不重复通知）；否则写 24h 墓碑（含会话快照 + 当时登录的客户端集合）、把 sid 记入 `sso:user-revocations:{sub}`、删除会话本体与 clients 集合、从用户 ZSET 索引移除。
   - 对墓碑里**每个**已登记客户端：若其配置了 `backchannelLogoutUri`（且在允许的企业 CIDR 内），向 `sso_logout_event` 表写一条 Outbox 事件，payload 是预签好的 `logout_token`（10min，jti=事件 id，幂等）。
   - Outbox 全部落库后才清理墓碑；落库失败则墓碑保留，下次调用/恢复任务可补齐——**登出通知不丢的保险丝**。
   - 生成一次性 `logout_request`（30s，含 sid + 回跳地址）。
4. 返回 `{"logout_request": "<一次性票>"}`。

**之后的后台动作**：SSO 的 `LogoutOutboxService` 每 10 秒扫表，按 30 秒租约原子抢占后，POST `{"logout_token":"..."}` 到各客户端的 backchannel 地址；失败按 **1m/5m/30m/30m/30m** 退避，最多 5 次后转「死信」(status=9)。每次重投用**同一 jti 重新签发**短期 token（退避总时长可能超过 token 有效期），客户端按 jti 去重。

---

### 2.4 `GET /sso/v1/logout?request=` —— 浏览器清 Cookie

**一句话**：拿着上一步的小票，原子消费、核对 Cookie 后清掉 `SSO_SID`，再跳回 B 系统的登出完成页。

**调用流程**：

1. `consumeBrowserRequest`：Lua 式 GETDEL 原子消费一次性小票（30s 过期，用过即焚）→ 无效/过期 → `400`。
2. **常量时间比较** Cookie 里的 `SSO_SID` 与小票里的 sid：
   - 相等 → 清 `SSO_SID` Cookie（总门票作废，下次再走 SSO 要重新登录门户）；
   - 不等（比如浏览器里是别人的旧 Cookie）→ **只清本地这个过期 Cookie，绝不吊销别人的会话**（防「拿 A 的小票把 B 的会话清了」）。
3. `302` 到小票里已验证过的 `postLogoutRedirectUri`。

> 为什么登出要 2.3 + 2.4 两步？因为**吊销会话**是后端对后端的事（要 HMAC 验签），**清 Cookie** 只能由浏览器自己发 GET。一次性小票把两件事安全地串起来，且无法被伪造或重放。

---

### 2.5 `GET /sso/v1/jwks.json` —— 发布公钥

- 返回 JWKS：当前 `current-kid`（默认 `2026-01`）+ 配置里**显式列出且未过截止时刻**的额外 kid（支持密钥轮换过渡期双钥并行）。密钥目录里的其它 JWK 绝不自动发布。
- 响应头 `Cache-Control: no-store`（公钥变更必须即时可见）。
- **注意**：B 端的生产信任仍以**本地预置的公钥指纹白名单**为准，JWKS 只作参考/辅助——防「SSO 服务器被入侵后换一把自己签的公钥」这种供应链攻击。

---

### 2.6 `GET /sso/v1/health/readiness` —— 就绪探活

- 检查两件事：`Redis PING` 返回 PONG + 当前私钥可用（能签发）。
- 全过 → `200 {"status":"UP"}`；任一失败 → `503 {"status":"DOWN"}`。
- 用途：B 端拦截器/运维据此判断 SSO 是否可服务，做降级或告警。它探的是「SSO 现在还能不能干活」，不是「进程活着」。

---

### 2.7 `POST /sso/v1/session/status` —— 敏感操作前查会话

**一句话**：B 系统在执行敏感操作（比如转账）前，回头问 SSO「这个 sid 现在还算数吗？」，SSO 回一张签名过的状态 JWS。

**调用流程**（HMAC 验签同 2.2）：

1. 按 body 里的 `sid` 查 `requireActive`（存活？未禁用？）。
2. `active = 会话存活 且 该 clientId 已登记进该 sid`——**匿名查询/旁路客户端查别人会话，active 永远是 false**，但接口仍正常返回 200（不泄露「会话存在与否」之外的信息）。
3. 返回 RS256 签名的 `session_status` JWS（60s）：`{client_id, sid, active, request_nonce}`——**active 字段也被签名**，防止中间链路篡改「active=true」蒙混过敏感操作。

---

## 3. 内部回环接口（`InnerSsoController`，3 个，仅 127.0.0.1:19089 + `X-INNER-TOKEN`）

> 由 `InnerAuthFilter` 强制保护：先校验回环来源，再比对 `X-INNER-TOKEN`。调用方只有两个——**门户登录/登出钩子**（`PortalSsoInnerClient`）和 **System 用户状态 Outbox**（`UserStatusOutboxService`），都是同机进程，RestTemplate 超时 2s/2s。

### 3.1 `POST /internal/sso/beginSession` —— 建全局会话

**调用方**：门户登录成功后（`LoginController.login` → `attachSsoSession` 钩子）。

**请求体**：`sub`（用户 id）、`username`、`pending`（可选，来自登录 URL 的 `_sso` 参数）、`old_sid`（可选，该浏览器上次的会话）。

**调用流程**：

1. `sub`/`username` 缺失 → `400`。
2. **旧会话处理**：若带 `old_sid`，先 `requireActive` 查旧会话，**且旧会话的 sub 与本次 sub 相同**才吊销（`revokeAndNotify`，含通知）。——防「拿别人的 oldSid 来吊销别人的会话」这种跨用户攻击。
3. **建新会话**（`SessionService.create`，Lua 原子）：
   - 脚本第一步就检查 `sso:subject-disabled:{sub}`——**禁用事件一旦落到 Redis，并发中的登录在同一原子边界被拒**（不需要额外的锁）；
   - 否则写 `sso:session:{sid}`（TTL = min(距绝对过期剩余, 30min)）+ `ZADD sso:user-sessions:{sub}`（score=绝对过期时刻）。
   - 用户已禁用 → 返回 `403`。
4. **补发 code**：若带 `pending` 且暂存单还在（`getAndDelete` 原子取走）→ 用新 sid 发 90s code → 拼出 `redirectUrl`（= 浏览器最终要跳的 B 回调地址）。
5. 返回 `{sid, expires_at, redirect_url?}`。门户侧接着种 Cookie（见 4.1），若 `redirect_url` 非空则放进登录响应的 `ssoRedirect` 字段，前端拿到后直接跳 B 系统完成闭环。

### 3.2 `POST /internal/sso/revokeUserSessions` —— 用户级回收

**调用方**：System 用户状态 Outbox（管理员禁用/删除/解冻用户后，后台任务投递）。

**请求体**：`sub`、`event_type`（`USER_DISABLED` / `USER_DELETED` / `USER_ENABLED`）。

**调用流程**：

1. `sub` 缺失或事件类型未知 → `400`。
2. **解冻**（`USER_ENABLED`）：删掉 `sso:subject-disabled:{sub}` 门禁，该用户重新可以登录/建会话；返回 `revoked=0`。
3. **禁用/删除**（`USER_DISABLED`/`USER_DELETED`）：
   - `disableSubject`（Lua 原子）：**先** `SET sso:subject-disabled:{sub}`（关门禁——此刻起任何并发登录都建不出新会话），**再** `ZREMRANGEBYSCORE` 清掉已自然过期的 sid，**再**返回「有效 sid（ZSET 剩余）+ 撤销通知待补齐的 sid（user-revocations）」合并快照；
   - 逐个 `revokeAndNotify(sid)`：写墓碑（幂等）→ 给每个登录过的客户端补登 Outbox 通知（流程同 2.3 第 3 步）→ 成功落库后清墓碑；
   - 返回 `{sub, revoked: 实际吊销数}`。

> 为什么「先门禁后快照」？反过来做的话，在「取快照」和「关门禁」之间登录进来的新会话会漏网。现在的顺序保证：**门禁落下之后，不可能再有新的有效会话诞生**。

### 3.3 `POST /internal/sso/logout` —— 门户登出

**调用方**：门户登出钩子（`LoginController.logout`）。

**请求体**：`sid`。

**调用流程**：`revokeAndCreateBrowserRequest(sid, "portal", 门户post-logout页)`——即 2.3 第 3 步的同一套逻辑：吊销会话 + 给所有登录过的客户端排队 backchannel 通知 + 生成一次性 `logout_request`（回跳地址固定为门户登录页，不接受调用方指定）。返回 `{logout_request}`，门户拼成 `http://192.9.230.21:10089/sso/v1/logout?request=...` 放进登出响应的 `ssoLogoutRedirect`，前端引导浏览器走完 2.4 清 Cookie。

---

## 4. 门户/System 侧（没有新增 HTTP 接口，只有钩子）

> 全部带 `@ConditionalOnProperty(sso.integration.enabled=true)`；**任何 SSO 异常都只记日志，绝不阻断原有本地流程**（降级为普通门户登录/登出）。

### 4.1 登录钩子（`LoginController.login` → `attachSsoSession`）

原登录流程（账号密码/验证码/Shiro/JWT）**一行不动**，JWT 签发成功**之后**追加：

1. 从响应里取新签发的门户 token；
2. 找旧会话：先查 `portal:sso-map:{sha256(旧token)}`，查不到再读 `PORTAL_SSO_REF` Cookie（同浏览器重新登录时取回 oldSid）；
3. 回环调 `beginSession(userId, username, pending, oldSid)`（`loginModel.getPending()` 即登录页 URL 里的 `_sso` 值）；
4. 种**两枚** Cookie（都 HttpOnly + SameSite=Lax）：
   - `SSO_SID=...; Path=/sso/v1`——Path 收窄到 SSO 路径：门户自己的页面（10088）收不到它，只有浏览器访问 `10089/sso/v1/**` 时才会带上（Cookie 跨端口共享，同 host 下 10088 种的 Cookie 在 10089 也可见，这正是设计意图）；
   - `PORTAL_SSO_REF=...; Path=/`——门户服务端专用引用，供下次登录/登出时找回 sid（SSO 侧仍会校验 oldSid 归属，防滥用）；
5. `portalSsoMapService.save(token, sid, min(CookieMaxAge, 本地token剩余寿命))`——Redis 里存 **token 哈希→sid** 映射（Key 不存明文 token）；
6. 若 `beginSession` 返回了 `redirectUrl`（说明是 SSO 拉起来的登录），放进登录响应的 `ssoRedirect`，前端直接跳 B 系统。

### 4.2 登出钩子（`LoginController.logout`）

本地清理（删 token 缓存、Shiro 登出等）**之前**先：

1. 找 sid：`portal:sso-map:{sha256(token)}` → 兜底 `PORTAL_SSO_REF` Cookie；
2. 回环调 `/internal/sso/logout` → 得到一次性 `logout_request` → 拼 `ssoLogoutRedirect`；
3. 删映射 + 清 `PORTAL_SSO_REF` Cookie；
4. 把 `ssoLogoutRedirect` 放进登出响应 payload，前端引导浏览器访问它（走 2.4 清 `SSO_SID`）。

失败 → `log.warn("SSO全局登出失败，继续执行本地登出")`，本地登出照常完成。

### 4.3 用户状态钩子（`SysUserController` 删除/批量删除/冻结）

```
管理员操作
   │
   ▼
SsoUserStatusCoordinator（@Transactional，仅 SSO 启用时存在）
   │  同一事务：① 更新 sys_user（删/冻结/解冻）
   │           ② user_status_event 表写 USER_DELETED / USER_DISABLED / USER_ENABLED
   ▼
UserStatusOutboxService.deliverDueEvents()（@Scheduled 每 10s，批量 100）
   │  ① 按 subject 保序：同一用户有未投递的前序事件时，后序事件（如"解冻"）必须排队，
   │     不能让解冻越过还在重试的禁用
   │  ② 条件 UPDATE 原子抢占（30s 租约）——多实例不会并发处理同一条
   │  ③ 回环 POST /internal/sso/revokeUserSessions（X-INNER-TOKEN）
   │  ④ 2xx → 标记已投递；异常 → 按 60s/300s/1800s/1800s/1800s 退避重试，
   │     5 次仍失败打 error 日志留表（status=2），人工介入
   ▼
SSO 侧接口 3.2 执行门禁 + 批量吊销 + backchannel 通知
```

> 为什么用「同事务写表 + 后台投递」（Outbox 模式）而不是操作里直接 HTTP 调 SSO？因为「改用户表」和「通知 SSO」跨了两个服务，直接调会出现在一边成功一边失败的窗口；Outbox 把两者绑进**同一个本地事务**，投递失败永远可以从表里重放，最终一致。

---

## 5. 场景走读（把 10 个接口串成故事）

### 场景 A：用户第一次点进 B 系统（未登录）

```
浏览器        门户10088              SSO(10089公开口)        B系统后端
  │ 1.点"进B系统"      │                        │                    │
  │ 2.302 GET /sso/v1/authorize?client_id&redirect_uri&state&code_challenge
  │──────────────────────────────────────────────▶│ 3.验客户端/参数
  │                                              │ 4.读SSO_SID→无会话
  │                                              │ 5.存pending(10min)
  │ 6.302 → 门户/login?_sso=xxx                  │
  │◀─────────────────────────────────────────────│
  │ 7.输账号密码 POST /login(pending=xxx)        │
  │──────────▶ 8.本地登录成功(原流程不动)         │
  │           9.钩子: 回环19089 /internal/sso/beginSession
  │──────────────────────────────────────────────▶│ 10.建sid(4h/30min)
  │                                              │   消费pending→发code(90s)
  │ 11.响应: Set-Cookie SSO_SID+PORTAL_SSO_REF   │
  │      body.ssoRedirect = B回调?code&state     │
  │◀──────────                                   │
  │ 12.前端跳转 → B回调?code&state               │
  │─────────────────────────────────────────────────────────────────▶│
  │                                   13.POST /sso/v1/exchange        │
  │                                   (code+verifier, HMAC头)
  │◀──────────────────────────────────────────────│ 14.消费code→层层校验
  │                                              │   →登记client→发assertion(60s)
  │                                     15.验assertion指纹/nonce
  │                                     →发B系统自己的token → 进B系统
```

关键点：浏览器只经手过 **code**（90s 一次性）和两枚 Cookie；真正的身份证据 assertion 只出现在 B 后端与 SSO 之间，60s 且绑 nonce。

### 场景 B：已登录门户，再次点进 B 系统

`authorize` 第 4 步命中：读 Cookie 的 sid 有效 → 直接发 code → 302 回 B 回调 → `exchange`。**全程无登录表单、无门户参与**，这就是「单点」的体验。用户随后 30 分钟内不操作则空闲过期，4 小时绝对过期。

### 场景 C：用户在 B 系统里点「退出」

```
B前端 → B后端：调 /sso/v1/logout(HMAC, sid, post_logout_uri)
SSO：验签→验回跳绑定→验client已登记该sid
     →吊销会话(墓碑24h+客户端快照)
     →给每个登录过且配了backchannel的客户端写登出Outbox(排1m/5m/30m退避)
     →返回一次性logout_request(30s)
B后端 → 302浏览器 GET /sso/v1/logout?request=xxx
SSO：原子消费小票→Cookie SID一致才清SSO_SID→302回B登出完成页
后台：SSO Outbox任务逐条POST logout_token给各B客户端(同jti幂等去重)
```

效果：用户从 B 退出后，`SSO_SID` 没了；**其他已登录的 C 系统也会收到 backchannel 通知而登出**（全局登出）。

### 场景 D：管理员禁用某用户

```
管理员点"冻结" → SysUserController
 → SsoUserStatusCoordinator.updateFrozenUsers（同事务：改状态 + 写USER_DISABLED事件）
10s内 UserStatusOutboxService 投递 → 回环 /internal/sso/revokeUserSessions
 → SSO：①关门禁 sso:subject-disabled（并发登录即刻建不出会话）
        ②快照该用户全部sid（ZSET+待补通知）
        ③逐个吊销(幂等) ④每个登录过的客户端排 backchannel 登出通知
返回 revoked 计数 → 事件标记已投递
```
用户此刻在 B 系统里刷新/做敏感操作：B 端调 `session/status` → `active=false` → B 端自行登出；即使不调，下次任何 `requireActive` 也会因门禁直接失效。

### 场景 E：管理员解冻该用户

同链路，`event_type=USER_ENABLED` → SSO 删除门禁 → `revoked=0`。用户重新走门户登录即可。Outbox 的「按用户保序」保证：如果之前的禁用事件还在重试队列里，解冻事件必须等它先送达，**不会**出现「解冻先生效、禁用后生效」把用户卡死的状态。

---

## 6. 常见疑问（为什么这么设计）

1. **code 和 assertion 为什么是两个东西？** code 是「浏览器可携带的取货凭证」（一次性、90s）；assertion 是「后端间验签的身份证据」（60s、RS256、绑 nonce）。分离后浏览器链路不出现任何长期/可重放凭证。
2. **为什么登出要 POST + GET 两步？** 吊销会话是后端行为（需 HMAC 验签），清 Cookie 只能浏览器做。一次性小票把两者安全串联：不能重放、不能拿 A 的小票清 B 的 Cookie。
3. **为什么种两枚 Cookie？** `SSO_SID`（Path=/sso/v1）给 SSO 的 authorize 判断「已登录」用，门户页面永远收不到它；`PORTAL_SSO_REF`（Path=/）给门户服务端找回 oldSid 用（登出、重登换会话）。职责分离，且都是 HttpOnly。
4. **exchange 时为什么要登记 client 到 session-clients？** 三个用途：① 登出时「没登录过的客户端无权吊销」（403）；② `session/status` 的 active 判定；③ 吊销时知道该给哪些客户端发 backchannel 通知。
5. **为什么内部接口只走回环 + token？** 设计稿 V1.2 冻结「固定同机回环」：回环地址物理上把可达集合缩到同一台机器，token 是第二道闸，`/internal/**` 在 Nginx 侧 404 是第三道闸。三道叠加，且零服务发现依赖（详见 `.work\SSO内部回环与OpenFeign对比.md`）。
6. **禁用用户为什么「先门禁后快照」？** 顺序反了会在间隙里漏掉新会话。Lua 原子脚本保证门禁落下后不可能再有新有效会话。
7. **撤销墓碑（24h）是干嘛的？** 两个作用：幂等（同一 sid 重复登出不重复通知）+ 兜底（登出事件写 MySQL 失败时，快照还在，可补齐后再清理——通知不丢）。
8. **门户的 token 哈希映射有什么用？** 让门户服务端能反查「这个门户 token 对应哪个 sid」，用于登出/重登换会话；Key 只存 SHA-256 哈希，泄露 Redis 也换不回 token 或 sid 的直接关联以外的信息。
9. **`session/status` 查别人的会话会怎样？** 正常返回 200，但 `active=false` 且整张 JWS 带签名——既不能旁路探活别人的会话，中间链路也篡改不了 active 字段。
10. **SSO 挂了门户还能用吗？** 能。所有 SSO 钩子都是「本地流程成功之后」的追加动作，失败只记日志降级；代价是该用户暂时无法完成 SSO 闭环（B 系统进不去），门户自身登录/业务不受影响。

---

## 7. 接口总表（速查）

| # | 方法+路径 | 门 | 调用方 | 一句话 | 关键时效 |
|---|---|---|---|---|---|
| 1 | `GET /sso/v1/authorize` | A | 浏览器 | 授权入口：有会话发 code，无会话存 pending 送登录页 | code 90s / pending 10min / 重试 3 次 |
| 2 | `POST /sso/v1/exchange` | A | B 后端（HMAC） | code 兑身份：六层校验后发签名 assertion | assertion 60s 绑 nonce |
| 3 | `POST /sso/v1/logout` | A | B 后端（HMAC） | 发起全局登出：吊销 + 排 backchannel 通知 + 发清 Cookie 小票 | 小票 30s |
| 4 | `GET /sso/v1/logout?request=` | A | 浏览器 | 原子消费小票，SID 一致才清 Cookie，302 回跳 | 同上 |
| 5 | `GET /sso/v1/jwks.json` | A | B 后端/运维 | 发布 RS256 公钥（no-store） | 轮换期双 kid |
| 6 | `GET /sso/v1/health/readiness` | A | 探活方 | Redis PING + 私钥可用 → UP，否则 503 | — |
| 7 | `POST /sso/v1/session/status` | A | B 后端（HMAC） | 敏感操作前查 sid 是否有效（签名 JWS 返回） | 60s |
| 8 | `POST /internal/sso/beginSession` | B | 门户登录钩子 | 建全局会话 + 旧会话同属主才换 + 补发 code | 4h/30min |
| 9 | `POST /internal/sso/revokeUserSessions` | B | System Outbox | 解冻开门禁；禁用/删除先门禁后快照逐个吊销 | 墓碑 24h |
| 10 | `POST /internal/sso/logout` | B | 门户登出钩子 | 按 sid 吊销 + 生成浏览器清 Cookie 小票 | 小票 30s |

**链路口诀**：`1 进 → 2 换 → 7 查 → 3+4 出`，`5 发钥匙`，`6 探活`；内部 `8 建`、`9 回收`、`10 门户出`。
