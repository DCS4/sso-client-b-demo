# 统一认证 SSO 设计文档

> *jeecg-sso · HTTP 内网版 · V1.2 冻结稿*

| 文档属性 | 内容 |
|---|---|
| 版本 | **V1.2（冻结稿）** |
| 定调 | **V1 采用企业受控内网 HTTP 定稿**，HTTPS 不作为上线阻断条件 |
| 部署形态 | jeecg-sso 独立程序包；Nginx 对外双端口，应用绑定回环地址；门户、System 与 SSO 同机同 IP，B 系统部署在任意已登记的其它内网 IP |
| 参考部署 | 门户 `http://192.9.230.21:10088`，SSO `http://192.9.230.21:10089`，System 同机回环调用，B `http://192.9.230.35:8080` |

## 冻结声明（融合两份评审的最终口径）

> **V1 第一阶段采用企业受控内网 HTTP 部署。门户、System 与 SSO 部署在同一台 `192.9.230.21` 主机：门户与 SSO 通过同 IP 不同端口、Cookie 不按端口隔离的浏览器机制建立全局会话；System 固定经回环调用 SSO 内部接口。业务客户端部署在任意已登记的其它内网 IP。由于环境暂不具备证书条件，Cookie 不设置 Secure。为降低 HTTP 明文传输风险：客户端后端请求采用 HMAC-SHA256 签名（不传输明文 secret）；身份交换响应与登出通知采用**预置公钥验证**的 JWS 签名；授权码链路保留 state、PKCE、精确回调匹配、Redis GETDEL；全局会话缩短为 4 小时。方案仍无法消除 SSO_SID 被内网截获后重放的风险，该风险作为 V1 已知限制登记；未来具备证书条件后升级 HTTPS，协议主体不变。**

## V1.2 相对 V1.1 的修改摘要

| # | 修改 | 来源 |
|---|---|---|
| 1 | **部署基线从“HTTPS A / HTTP B 二选一”改为“V1 = HTTP 定稿，HTTPS 是 V2 升级路径”** | 第二份评审定调（覆盖第一份 P0-1） |
| 2 | 删除 HTTPS 机器名证书 / IP SAN / 301 证书陷阱相关条款（HTTP 下 301 归一逻辑不受 TLS 握手前阻断，保留） | 第二份；第一份 P0-2 失效 |
| 3 | 删除 Basic 明文 secret，全面改用 **HMAC 请求签名**（时间戳+nonce+body hash），DB 增加 `client_hmac_key_cipher` | 第二份 §三 |
| 4 | `/exchange` 响应从普通 JSON 改为 **60 秒短命签名 identity_assertion** | 第二份 §四 |
| 5 | JWKS 策略从“未知 kid 自动刷新信任”改为“**预置公钥指纹白名单，非白名单 kid 拒绝并告警**” | 第二份 §五 |
| 6 | 全局会话 8h → **4h 绝对 + 30m 空闲**；全部时效改为配置值 | 第二份 §六 |
| 7 | `session-clients` 登记时机从 beginSession/pending 移到 **exchange 成功时** | 第一份 P0-3 |
| 8 | logout_request 从“仅 sid”扩展为 **{sid, client_id, post_logout_redirect_uri, issued_at} 绑定结构**，客户端表加 post_logout_redirect_uri | 第一份 P0-4 |
| 9 | System 离职/禁用通知增加 **同事务 Outbox**（user_status_event），固定经同机回环调用 SSO | 第一份 P0-5 |
| 10 | **sub 统一定义为 `sys_user.id`**，DTO/Redis/DB/日志/协议全部用 sub，代码中 userId 全部替换 | 第一份 P0-6 |
| 11 | Redis 索引 TTL 显式化：session-clients 用 `EXPIREAT`，user-sessions 改 **ZSET（score=过期时间戳）+ ZREMRANGEBYSCORE 清理** | 第一份 §八 |
| 12 | 门户 Token 映射改 **`portal:sso-map:{SHA256(token)}`**；TTL = min(SSO 剩余， 本地 Token 剩余)；oldSid 校验归属后再吊销 | 第一份 §九 |
| 13 | 回调拼接改 **URI Builder**（保留登记 URI 原有 query，参数编码） | 第一份 §十-2 |
| 14 | Readiness 外部只返回 `{"status":"UP"}`，组件详情限本机/运维 | 第一份 §十-3 |
| 15 | Logout Token 校验补 `events` 断言、禁 `nonce`、jti 幂等、白名单 kid | 第一份 §十一 |
| 16 | 新增 §十 HTTP 运维纪律：日志脱敏、Cache-Control 等响应头、网段准入、限流与告警 | 第二份 §八 |
| 17 | `/session/status` 正式入接口清单（POST + 客户端签名，禁匿名查询） | 第一份 §十-1 |

---

## 一、背景

### 1.1 业务背景

公司内部运行多个业务系统（报表、OA、CRM 等，技术栈不限）。当前问题：每系统各自登录、离职需逐系统注销、新系统重复开发认证模块。

目标：登录一次全系统免密；一处登出全局失效；**各业务系统保留自身权限体系（角色、菜单、数据权限），SSO 只证明"你是谁"**。

### 1.2 技术背景

现有 JeecgBoot 微服务体系：Nacos、Gateway、Feign、共享 Redis、MySQL。门户已有完整登录页与登录逻辑（账号密码+验证码），系统内已有"其它系统 URL 登记"功能，存量资产全部复用。

SSO 采用**"中央认证 + 客户端本地会话"**：jeecg-sso 管全局会话、签发一次性授权码；客户端兑换**签名身份断言**后自建本地登录态（JeecgBoot 系沿用 JwtUtil+Shiro，异构系统用各自 Session/JWT）；**不签发中央 Access/Refresh Token，不提供 userinfo，业务接口零改造**。

### 1.3 部署决策与信任域声明

依据 RFC 6265 §8.5：Cookie 按主机字符串隔离，**端口不参与隔离**。同 IP 双端口使门户登录响应可直接种 SSO 会话 Cookie。同时按 RFC 的 SHOULD NOT 警告正式声明：

> **门户与 SSO 视为同一安全信任域。192.9.230.21 上不得再部署不受信任的第三方 Web 服务、厂商演示页或任何可写 Cookie 的未知应用**；可接受共存者仅限门户、SSO、受控 Nginx。

#### 同 IP 与不同 IP 的职责边界

B 在其他 IP 不但成立，且正好是安全边界——B 不共享 SSO Cookie，只经一次性 code + 后端签名交换参与。

| 内容 | 门户 `.21` | SSO `.21` | B `.35` |
|---|---|---|---|
| 收到 SSO_SID | 仅 `/sso/v1/**` 路径 | 能 | 不能 |
| 需读 SSO_SID | 不需要（本地 Token→sid 映射） | 需要 | 不需要 |
| 浏览器侧得到 | 门户本地 Token | SSO Cookie | 一次性 code |
| 后端侧得到 | sid 映射 | 全局会话 | 签名身份断言 |
| 本地会话 | Jeecg JWT+Shiro | Redis SSO Session | B 自实现 |
| 登出依据 | Token→sid | sid | 本地Session→sid |

#### 唯一入口机制

① Nginx 两 server 块均 `if ($host != "192.9.230.21") { return 302 ...; }`（试点用 302/307 不用 301，防 IP 迁移被浏览器永久缓存拖累，稳定后再评估 301；HTTP 下无 TLS 握手前置阻断，该归一可正常执行）；② 出向 Location 一律写 `192.9.230.21`（host 统一**只约束跳到 SSO 的目的地**，与 B 回调地址无关）；③ 回调/登出地址按 §四 SSRF 规则录入。

---

## 二、总体架构

### 2.1 部署拓扑

```text
服务器 192.9.230.21
┌──────────────────────────────────────────────────┐
│  Nginx（对外唯一暴露层，HTTP）                      │
│  :10088 → 127.0.0.1:19088（门户全部路径）           │
│  :10089 → 127.0.0.1:19089，★仅代理 /sso/v1/**      │
│           /internal/** 一律 404                    │
│  日志：$uri 不记 $request_uri（脱敏，见 §十）        │
├──────────────────────────────────────────────────┤
│  门户应用 127.0.0.1:19088                          │
│    原登录逻辑 + 登录/登出钩子 + token哈希→sid 映射    │
├──────────────────────────────────────────────────┤
│  SSO 应用 127.0.0.1:19089（jeecg-sso）             │
│    /sso/v1/**（协议接口）+ /internal/**（回环专用）  │
├──────────────────────────────────────────────────┤
│  System（用户管理服务，同机部署）                    │
│    用户状态 Outbox → 127.0.0.1:19089 /internal/**   │
│    X-INNER-TOKEN                                    │
└──────────────────────────────────────────────────┘
共享：Redis（sso: 前缀）、MySQL（SSO 三表+System事件表）、Nacos
```

### 2.2 内部调用决策（固定同机回环）

| 调用方 | 通道 | 说明 |
|---|---|---|
| 门户 → SSO beginSession / revoke | 直连 `http://127.0.0.1:19089` + X-INNER-TOKEN | 同机回环，最简可靠 |
| **System（用户禁用/离职）→ SSO revokeUserSessions** | 直连 `http://127.0.0.1:19089` + X-INNER-TOKEN | 固定同机回环；不经公开 `10089`，不开放额外监听端口 |

上述内部调用均不走 Feign/Nacos（回环地址注册进 Nacos 的实例仅单机拓扑碰巧可用，语义混淆）。

### 2.3 流程全景

```text
【场景A：直接登录门户】
门户原校验通过 → 门户取 oldSid（受信来源）→ 回环调 beginSession
  （校验 oldSid 归属同一 sub 后吊销旧会话；建新 sid + user-sessions 索引）
→ 门户响应 Set-Cookie: SSO_SID（Path=/sso/v1，Max-Age=配置的剩余时间）
→ 门户存 portal:sso-map:{SHA256(token)}→sid（TTL=min(SSO剩余,本地剩余)）→ 进入门户
【场景B：被 B 踢到 SSO】
B → 302 authorize（出向 host 统一）→ 浏览器带 SSO_SID → 有会话直接发 code
  → 302 回 B 登记回调（URI Builder 拼接）
→ B 后端 POST exchange（HMAC 签名 + PKCE verifier）
→ SSO：验签 → GETDEL 原子消费 code → 比对绑定 → 验会话
  → ★此时 SADD session-clients + EXPIREAT → 返回签名 identity_assertion
→ B 验 assertion（预置公钥 + nonce 回对）→ JIT/映射用户 → 建本地会话
（无 Cookie：暂存 pending（服务端含重试计数）→ 302 门户登录页 → 场景A+发 code）
【场景C：全局登出（完整闭环）】
① B 清本地会话
② B 后端 POST /sso/v1/logout（HMAC 签名，sid + client_id + post_logout_redirect_uri）
   → SSO 校验：客户端签名 ✓ sid 存在 ✓ session-clients 含此 client ✓
     post_logout_redirect_uri 与登记完全相等 ✓（无权注销未登录过的 sid）
   → 吊销会话 + 从 user-sessions 移除 + 写 Outbox
   → 返回一次性 logout_request = {sid, client_id, post_logout_redirect_uri, issued_at}
③ B 302 浏览器到 GET /sso/v1/logout?request=xxx
   → SSO GETDEL 消费 request → 比较 Cookie SID 与 request SID
   → 一致：清 Cookie（同 Path，Max-Age=0）
   → 不一致/已过期：清本地旧 Cookie 但不据此吊销其他会话
   → 302 到 request 中已验证的 post_logout_redirect_uri
④ Outbox 异步发签名 Logout Token → 各端验签+jti幂等 → 按 sid 清本地
门户退出同构（从 token 映射取 sid）
【场景D：SSO 故障降级】
B 拦截器先探测 GET /sso/v1/health/readiness（只回 {"status":"UP"}）
  → 1 分钟 5 次失败 → 熔断 → 渲染"统一认证中心暂不可用"错误页
  → 30s 半开探测恢复
SSO authorize 重试计数存 sso:pending（服务端，不可被浏览器篡改），≥3 次错误页
门户钩子 catch：本地 Token 打"未建SSO会话"标记 + 告警
break-glass 为正式决策：普通用户仅 SSO；管理员保留少量审计化应急账号
```

---

## 三、协议安全设计（HTTP 下的核心补偿层）

### 3.1 客户端请求：HMAC-SHA256 签名（替代明文 Basic）

所有 B 后端 → SSO 的调用（exchange / logout / session/status）统一签名：

```text
X-SSO-Client: appB
X-SSO-Timestamp: 1788344400
X-SSO-Nonce: c6be583b96d943a3
X-SSO-Signature: Base64Url(HMAC-SHA256(signingKey, canonical))
canonical = "POST\n/sso/v1/exchange\nappB\n1788344400\nc6be583b96d943a3\n" + SHA256(原始请求体字节)
```

服务端验证顺序：查 client_id 与启用状态 → 时间戳 ±120 秒（全服务器 NTP 统一）→ Redis `SETNX sso:client-nonce:{clientId}:{nonce}` TTL 5 分钟 → 常量时间比较 HMAC → 之后才进入业务校验（GETDEL 消费 code 等）。连续签名失败触发告警（§十）。

### 3.2 exchange 响应：60 秒签名 identity_assertion

HMAC 只保护请求方向，防不了响应被篡改（如把 sub 从普通用户改成管理员）。因此 exchange 返回 JWS 签名的身份断言（与 Logout Token 共用同一套 RSA/JWKS 基础设施）：
```json
{
  "identity_assertion": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjIwMjYtMDEifQ..."
}
```

Claims：`iss, aud(=client_id), sub, username, name, sid, auth_time, session_expires_at, iat, exp(=iat+60s), jti, request_nonce(=本次请求的 nonce)`。

B 验证：RSA 签名 + kid 白名单 + iss + aud + exp + jti 一次性 + **request_nonce 与本次请求一致**（绑定断言到具体请求，防跨请求重放）。

**定位声明**：identity_assertion 不是中央 Access Token——只能一次性登录建会话、60 秒作废、不能调业务接口、不能刷新；B 验完即建本地 Session/JWT。V1 边界"无中央 Token"不破坏。

### 3.3 公钥分发：预置指纹白名单（HTTP 下禁止盲信 JWKS）

HTTP 下攻击者可同时替换断言、logout_token 和 jwks.json，"未知 kid 自动刷新信任"会使签名保护失效。V1 规则：

1. SSO 生成 RSA 密钥对，公钥经**部署包/运维/线下安全渠道**分发各 B；
2. B 本地维护预置信任列表 `{kid → 公钥指纹}`；
3. jwks.json 仅作查询便利，**刷新后只有指纹在白名单内的公钥才被接受**，未知 kid 拒绝并告警；
4. 密钥轮换流程：新公钥先安全分发到各 B → B 确认白名单 → SSO 才开始用新私钥签 → 旧公钥保留至旧断言/令牌全部失效。

### 3.4 时效总表（全部配置化，不硬编码）

| 数据 | 有效期 |
|---|---|
| SSO 全局绝对会话 | **4 小时**（配置 `sso.session.absolute-timeout: 4h`） |
| SSO 空闲超时 | 30 分钟 |
| B 本地绝对会话 | 不晚于 `session_expires_at` |
| B 本地空闲 | 30 分钟 |
| code | 90 秒 |
| pending | 10 分钟（内含重试计数） |
| state | 10 分钟、一次性 |
| client nonce | 5 分钟 |
| identity_assertion | 60 秒 |
| logout_request | 30 秒 |
| logout jti 去重（B 侧） | 24 小时 |

`beginSession` 返回 `expires_at`，门户据此设 Cookie Max-Age 与映射 TTL。

---

## 四、模块设计

### 模块卡片：jeecg-sso

```text
jeecg-sso/
├── pom.xml              # base-core/nacos/redis/mybatis-plus/httpclient/jose
├── src/main/java/.../
│   ├── SsoApplication.java
│   ├── controller/
│   │   ├── SsoAuthorizeController.java   # GET  /sso/v1/authorize
│   │   ├── SsoExchangeController.java    # POST /sso/v1/exchange（HMAC入/JWS出）
│   │   ├── SsoLogoutController.java      # POST /sso/v1/logout（后端HMAC）
│   │   │                                 # GET  /sso/v1/logout?request=（浏览器）
│   │   ├── SsoJwksController.java        # GET  /sso/v1/jwks.json
│   │   ├── SsoHealthController.java      # GET  /sso/v1/health/readiness
│   │   ├── SsoSessionStatusController.java # POST /sso/v1/session/status（HMAC）
│   │   ├── InnerSessionController.java   # POST /internal/sso/beginSession
│   │   └── InnerRevokeController.java    # POST /internal/sso/revokeUserSessions
│   ├── service/  # CodeService / SessionService / LogoutNotifyService
│   │             # / IdentityAssertionService / LogoutTokenService / SigningKeyService
│   │             # / ClientHmacVerifier
│   ├── mapper/   # sso_client / sso_client_redirect_uri / sso_logout_event
│   └── config/   # 回环 X-INNER-TOKEN 鉴权 / Redis常量 / 密钥管理 / 时效配置
└── src/main/resources/  # 绑定 127.0.0.1:19089
```

**接口清单**：

| 接口 | 方法·路径 | 认证 | 用途 |
|---|---|---|---|
| 授权入口 | GET `/sso/v1/authorize` | 无 | 查Cookie→发code；无会话暂存 pending（含重试计数）→302 门户登录页 |
| 换身份 | POST `/sso/v1/exchange` | HMAC | 原子消费 code → **登记 session-clients** → 返回 identity_assertion |
| 全局登出（后端） | POST `/sso/v1/logout` | HMAC | 校验绑定 → 吊销 → 写 Outbox → 返回 logout_request |
| 全局登出（浏览器） | GET `/sso/v1/logout?request=` | request | 消费 request → 比对 Cookie SID → 清 Cookie → 302 已验证地址 |
| 公钥查询 | GET `/sso/v1/jwks.json` | 无 | 查询便利；信任以各 B 本地指纹白名单为准 |
| 就绪探活 | GET `/sso/v1/health/readiness` | 无 | **仅返回 `{"status":"UP"}`**，组件详情限本机 |
| 会话状态 | POST `/sso/v1/session/status` | HMAC | 敏感操作前查 sid 存活，禁匿名 |
| 会话建立 | POST `/internal/sso/beginSession` | 回环 X-INNER-TOKEN | 吊销旧会话（校验归属）+ 建新会话 |
| 用户级吊销 | POST `/internal/sso/revokeUserSessions` | 回环 X-INNER-TOKEN | 遍历 user-sessions 全部吊销 |

**回调地址校验**：预登记 + 完全相等 + 内网 CIDR + SSRF 黑名单（回环/链路本地/0.0.0.0/组播/保留段/云元数据）；`backchannel_logout_uri` 与 `post_logout_redirect_uri` 同规则。**客户端 HTTPS 分级登记**：HTTP 客户端标"风险已审批"标记，新接入原则 HTTPS（V2 起强制）。

**exchange 核心顺序（含全部校验与登记）**：
```java
public ExchangeResult exchange(ExchangeRequest request) {
    clientHmacVerifier.verify(request);            // ① HMAC：查client→时间窗→nonce→常量时间比较
    CodePayload code = codeStore.getAndDelete(request.getCode());  // ② 原子消费
    requireEquals(code.getClientId(), request.getClientId());      // ③ 绑定比对
    requireEquals(code.getRedirectUri(), request.getRedirectUri());
    verifyPkce(code.getCodeChallenge(), request.getCodeVerifier()); // ④ verifier
    SsoSession session = sessionService.requireActive(code.getSid()); // ⑤ 会话存活
    requireEquals(session.getSub(), code.getSub());
    // ⑥ ★此时登记（覆盖静默授权/pending授权/全部客户端类型，同一路径）
    redis.opsForSet().add("sso:session-clients:" + session.getSid(), request.getClientId());
    redis.expireAt("sso:session-clients:" + session.getSid(), session.getExpiresAt());
    // ⑦ 返回签名断言
    return identityAssertionService.issue(session, request.getClientId(), request.getNonce());
}
```

**回调拼接（URI Builder）**：保留登记 URI 自身可能携带的 query，`code`/`state` 正确 URL 编码后追加，禁止裸字符串拼接。

### 模块卡片：门户改造

**原则**：保持账号密码、验证码、JWT 签发、Shiro 权限逻辑不变，仅在登录成功与退出阶段增加 SSO 协作逻辑。
```java
@PostMapping("/login")
public Result<JSONObject> login(@RequestBody SysLoginModel m, HttpServletResponse response,
                                 HttpServletRequest request) {
    // 原有校验、JWT 签发逻辑照旧 …
    String token = jwtUtil.sign(...);
    try {
        // oldSid 从受信来源取（请求头/服务端Session），不信请求体
        String oldToken = extractTokenFromRequest(request);
        String oldSid = oldToken == null ? null :
                (String) portalRedis.get("portal:sso-map:" + sha256(oldToken));
        BeginSessionResult r = ssoInnerClient.beginSession(
                /*sub*/ sysUser.getId(), sysUser.getUsername(), m.getPending(), oldSid);
        // SSO 侧校验 oldSid.sub == 本次 sub 后才吊销（防跨用户吊销）
        long maxAge = r.getExpiresAt() - now();
        response.addHeader("Set-Cookie",
                "SSO_SID=" + r.getSid() + "; Path=/sso/v1; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Lax");   // 无 Secure（HTTP）
        portalRedis.setEx("portal:sso-map:" + sha256(token), r.getSid(),
                Math.min(maxAge, localTokenRemaining(token)), TimeUnit.SECONDS);
        if (r.getRedirectUrl() != null) result.getResult().put("ssoRedirect", r.getRedirectUrl());
    } catch (Exception e) {
        log.error("SSO会话建立失败，降级为本地登录", e);   // 打标 + 告警
    }
    return result;
}
```

登出钩子：`sid = portalRedis.get("portal:sso-map:"+sha256(token))` → 回环调 revoke → 前端跳返回的 logout_redirect 清 Cookie → 原本地清理不动。

### 模块卡片：客户端接入（B 系统）

1. **登录过滤器**：无本地会话 → state + PKCE → 302 `http://192.9.230.21:10089/sso/v1/authorize`；
2. **回调**：原子校 state → POST exchange（HMAC+verifier）→ 验 identity_assertion（白名单公钥 + request_nonce 回对）→ JIT/映射用户 → 建本地会话（绝对过期 = `session_expires_at` 直抄，另 30m 空闲）；
3. **主动登出**：清本地 → POST `/sso/v1/logout`（HMAC，sid + client_id + post_logout_redirect_uri）→ 前端跳 logout_redirect；
4. **登出通知回调**：验 Logout Token（见 §四下）→ 本地 jti 去重（SETNX，重复返回 204）→ 按 sid 清本地 → 204；
5. **用户表**：`sso_issuer + sso_subject` 唯一索引，JIT 开户默认最低权限、本地密码禁用。

   - **Logout Token 校验清单（B 侧，按 OIDC BCL）**：RSA 签名 + **kid 在本地预置白名单**（非白名单刷新 JWKS 后仍须指纹匹配才接受）→ iss → aud → iat 时间窗 → exp → **必须含 `sid`**（本方案 sub/sid 至少其一，V1 强制 sid）→ **`events` 必须含正确的 backchannel-logout 断言** → **不得包含 `nonce`**（含则拒绝）→ jti 本地 SETNX 幂等 → 按 sid 清会话。

---

## 五、数据库设计

```sql
-- ① 客户端登记表（HMAC 版）
CREATE TABLE sso_client (
  id              VARCHAR(36)  PRIMARY KEY,
  client_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '系统代码',
  client_name     VARCHAR(128) NOT NULL,
  client_secret_hash     VARCHAR(64)  COMMENT 'HTTPS V2 Basic 兼容，V1 可空',
  client_hmac_key_cipher VARCHAR(512) NOT NULL COMMENT '应用主密钥加密的HMAC密钥',
  client_hmac_key_version VARCHAR(32)  NOT NULL,
  secret_expires_at      DATETIME,
  status          INT DEFAULT 1,
  transport       VARCHAR(8) DEFAULT 'http' COMMENT 'http=风险已审批 / https',
  allowed_claims  VARCHAR(255),
  backchannel_logout_uri VARCHAR(512),
  post_logout_redirect_uri VARCHAR(512) COMMENT '登出后回跳，完全相等匹配',
  create_time     DATETIME,
  update_time     DATETIME
);
-- 主密钥经服务器环境变量/受控配置注入，不与密文同库同表；支持版本轮换；
-- HMAC 密钥与登录兑换 secret 分离
-- ② 回调地址表（同 V1.1：完全相等 + 内网CIDR + SSRF校验）
CREATE TABLE sso_client_redirect_uri (
  id           VARCHAR(36) PRIMARY KEY,
  client_id    VARCHAR(64) NOT NULL,
  redirect_uri VARCHAR(512) NOT NULL,
  UNIQUE KEY uk_uri (client_id, redirect_uri)
);
-- ③ 登出事件 Outbox（完整版）
CREATE TABLE sso_logout_event (
  id            VARCHAR(64) PRIMARY KEY COMMENT 'jti',
  sid           VARCHAR(64)  NOT NULL,
  sub           VARCHAR(128) NOT NULL,
  client_id     VARCHAR(64)  NOT NULL,
  event_type    VARCHAR(32)  DEFAULT 'logout',
  logout_uri    VARCHAR(512) NOT NULL,
  event_payload TEXT         NOT NULL COMMENT '完整签名logout_token落库，重试复用同jti',
  status        INT DEFAULT 0 COMMENT '0待发 1成功 2重试中 9失败',
  retry_count   INT DEFAULT 0,
  next_retry_at DATETIME,
  last_error    VARCHAR(1000),
  delivered_at  DATETIME,
  create_time   DATETIME,
  update_time   DATETIME,
  UNIQUE KEY uk_event (sid, client_id, event_type),
  KEY idx_status (status, next_retry_at)
);
-- ④ System 侧：用户状态事件 Outbox（离职/禁用可靠投递，第一份评审 P0-5）
CREATE TABLE user_status_event (   -- 建在 System 库，与用户状态变更同事务写入
  id            VARCHAR(64) PRIMARY KEY,
  sub           VARCHAR(128) NOT NULL,
  event_type    VARCHAR(32) NOT NULL COMMENT 'USER_DISABLED / USER_DELETED / USER_UNLOCKED',
  status        INT DEFAULT 0,
  retry_count   INT DEFAULT 0,
  next_retry_at DATETIME,
  create_time   DATETIME,
  KEY idx_status (status, next_retry_at)
);
```

**离职/禁用可靠链路**：System 更新用户状态 → **同事务**写 user_status_event → 后台任务持续调 SSO `revokeUserSessions(sub)` 直至成功（失败指数退避，超限告警，**不丢事件**）→ SSO 遍历 `sso:user-sessions:{sub}` 逐 sid 吊销并写各客户端 logout Outbox。这解决了"SSO 恰好不可用时用户已禁用但会话仍活 4 小时"的窗口。

---

## 六、Redis Key 规范（sub 统一 + TTL 显式化）

**sub 定义冻结**：`sub = sys_user.id`（Jeecg 主键永久不变），DTO / Redis / DB / 日志 / 客户端协议**全部只用 `sub`**，代码中 `userId` 命名全部替换；`username` 永远不作为身份标识（会变）。

| Key | 结构 | TTL / 清理 | 用途 |
|---|---|---|---|
| `sso:session:{sid}` | String(JSON) | 4h 绝对 + 30m 空闲滚动 | 全局会话 |
| `sso:session-clients:{sid}` | SET | **SADD 后必须 `EXPIREAT` 到 `session.expires_at`**（SADD 本身不设 TTL）；**exchange 成功时登记** | 登出通知范围 |
| `sso:user-sessions:{sub}` | **ZSET：member=sid，score=过期时间戳** | 不设整键 TTL；**读前先 `ZREMRANGEBYSCORE key -inf now` 清过期**，再 `ZRANGE`；避免"新登录重设整集合 TTL"导致旧 sid 提前消失或过期残留 | 按用户定位全部会话（离职吊销） |
| `sso:code:{code}` | String(JSON) | 90s，GETDEL 原子 | 授权码（含 code_challenge/method/issued_at/expires_at） |
| `sso:pending:{uuid}` | String(JSON) | 10min | 暂存跳转请求 + **服务端重试计数** |
| `sso:logout-request:{req}` | String(JSON) = `{sid, client_id, post_logout_redirect_uri, issued_at}` | 30s，GETDEL | 浏览器清 Cookie 凭证（绑定结构，非裸 sid） |
| `sso:client-nonce:{clientId}:{nonce}` | String | 5min，SETNX | HMAC 防重放 |
| `portal:sso-map:{SHA256(token)}` | String(sid) | min(SSO剩余, 本地Token剩余) | 门户映射（**Key 用 token 哈希，防监控/慢日志/运维暴露完整凭据**） |

已删除：`sso:login-ticket`（同 IP 方案不用）；`sso:logout-jti`（jti 去重归各 B 本地）。

---

## 七、关键技术决策表

| 决策 | 选择 | 理由 |
|---|---|---|
| V1 传输 | **HTTP 定稿**；协议层补偿（HMAC + JWS + 预置公钥 + 短时效）；剩余风险书面登记 | 第二份评审定调；RFC 6749/OWASP 的 TLS 要求以"已知限制 + 升级路径"形式响应 |
| 部署形态 | Nginx 对外 + 应用回环绑定 | `/internal/**` 物理隔离；RFC 6265 同 host Cookie 互通支撑免登录 |
| System 通知通道 | 固定同机回环 `127.0.0.1:19089` + X-INNER-TOKEN | 不复用 10089；不开放额外监听端口 |
| 客户端认证 | HMAC-SHA256 签名（时间戳+nonce+bodyhash），密钥加密落库 | HTTP 下禁明文 secret |
| 身份交换响应 | 60s 签名 identity_assertion（含 request_nonce） | 防响应篡改/跨请求重放；不构成中央 Token |
| 公钥信任 | **预置指纹白名单**，JWKS 仅查询便利 | HTTP 下盲信 JWKS 会被整体替换击穿 |
| sub | `sys_user.id`，全链路唯一命名 | 防映射断链与实现漂移 |
| session-clients 登记 | **exchange 成功时**（SADD+EXPIREAT） | 统一覆盖静默/pending授权；登记=确已建会话 |
| 授权码 | GETDEL + PKCE 字段绑定 + verifier 验证 + URI Builder 拼接 | 防并发/重放/截获兑换/拼接错乱 |
| 登出 | 后端吊销（校验绑定）+ 浏览器清 Cookie + logout_request 绑定结构 + Outbox | 完整闭环；通知 best-effort，TTL 兜底 |
| 离职/禁用 | System 同事务 Outbox → revokeUserSessions → ZSET 索引 | 兑换实时拒 + 主动吊销 + TTL 三层，事件不丢 |
| 会话时长 | 4h 绝对 + 30m 空闲，全部配置化 | HTTP 下压缩 SID 截获重放窗口 |
| 统一入口 host | 全公司固定 `192.9.230.21`（出向跳转）；试点用 302 归一 | 浏览器按 host 字符串匹配；防 301 永久缓存拖累迁移 |
| 信任域 | .21 上不得部署不受信任服务 | RFC 6265 §8.5 |

---

## 八、里程碑验收计划

| 里程碑 | 内容 | 闸口（必须全过才进下一阶段） |
|---|---|---|
| M0 | 部署决策确认 | **System、门户与 SSO 同机部署已确认，通知固定走回环**；Redis ≥ 6.2；NTP 统一确认；统一 host 通告；HMAC 主密钥保管方案确认 |
| M1 | SSO 骨架+密钥+HMAC | jwks.json 可访问；readiness 只回 `{"status":"UP"}`；`:10089/internal/**` 得 404、回环带 token 可访问；**HMAC 签名/验签单测通过（含时间窗、nonce 重放、常量时间比较）** |
| M2 | 认证核心 | ① code 并发兑换 10 次仅 1 成功 ② 错误签名**不消耗** code ③ 90s 过期 ④ redirect_uri 尾多 `/` 被拒 ⑤ 错误 verifier 被拒 ⑥ 恶意 CIDR/SSRF 地址录入被拒 ⑦ **exchange 响应断言篡改被 B 检出** ⑧ **非白名单 kid 被拒并告警** |
| M3 | Cookie 链路 | F12 → `192.9.230.21` 下见 `SSO_SID`（Path=/sso/v1、HttpOnly、Max-Age≈4h 剩余）；门户 `/sys/**` 请求头**不带** SSO_SID（Path 收窄生效）；机器名访问被 302 归一且 Cookie 正常 |
| M4 | 免登录端到端 | 门户登录后访问 B（.35）静默进入；**重新登录生成新 SID 且旧 SID 立即失效**；**identity_assertion 的 request_nonce 不匹配时 B 拒绝** |
| M5 | 登出联动（可测指标） | 正常客户端登出事件 **≤5s** 失效；短暂故障客户端恢复后重试失效；长期故障客户端最迟 `session_expires_at`；**logout_request 的 post_logout_redirect_uri 不匹配时 SSO 拒绝**；**B 无权注销未登录过的 sid** |
| M5a | 风险专项演练 | ① 停 SSO → B 2 秒内友好错误页无循环 ② 停某 B → Outbox 重试→失败标记→TTL 兜底 ③ **离职演练：System 禁用（含 SSO 暂时不可用后恢复）→ 事件经 Outbox 补投 → 该用户所有已登录客户端收通知清会话，下次兑换被拒** ④ 密钥轮换：新公钥白名单分发后 B 无需重启可验新令牌，未分发时新 kid 被拒 |
| M6 | 上线切流 | 灰度→全量；**审计核对：登录 / exchange / 登出 / 客户端配置变更 / 签名失败告警 五类齐全**；**日志抽查无 SSO_SID / code / verifier / 签名 / 断言明文** |

---

## 九、HTTP V1 已知风险登记（正式条款，不做"等价方案"表述）

以下风险在 V1 HTTP 下**无法消除**，作为已知限制登记，V2 升级 HTTPS 解决：

- 门户登录账号密码可被链路截获；
- `SSO_SID` 可被截获并在有效期内重放（已通过 4h 绝对 + 30m 空闲 + 每次登录重生成 SID 压缩窗口）；
- B 的本地 Session Cookie 与页面流量不受保护（PKCE 不覆盖此面）；
- 网络内注入/篡改方向已由 HMAC（请求）与 JWS（响应）覆盖，但**会话 Cookie 劫持不可覆盖**。

---

## 十、HTTP 运维纪律

**日志脱敏**（Nginx 与应用，强制）：不记录 `Cookie`、`SSO_SID`、`code`、`state`、`code_verifier`、HMAC 签名、Logout Token、Identity Assertion、HMAC 密钥；SSO Nginx access log 记 `$uri` 不记 `$request_uri`。

**响应头**（登录 / authorize / callback / exchange 一律追加）：

```text
Cache-Control: no-store
Pragma: no-cache
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
```

**网络限制**：10088/10089 仅办公网与业务服务器网段；19088/19089 仅绑 `127.0.0.1`；B 的 Back-Channel 地址仅允许 SSO 服务器来源；exchange 按 client+IP 限流（超阈值 429）；连续签名失败告警；`.21` 主机不得部署其他不受信任 Web 服务（信任域声明）。

---

## 附录A：待复核确认清单（冻结前剩余两个事实项）

1. **内部 DNS 是否可推**（如 `sso.corp`）？V1 不阻断（IP 已定稿），但决定 V2 证书主体用 DNS 名还是 IP SAN；
2. **各 B 系统登记回调的 transport 现状**：现有 HTTP 老系统逐家打"风险已审批"标记，新接入按 HTTPS 原则登记。

---

**冻结声明**：V1.2 为冻结稿。核心架构（独立 jeecg-sso、不升级 JWT+Shiro、中央会话+本地授权、同 host Cookie、HMAC+JWS 协议补偿层、三层离职回收）不再变动；两份评审的全部采纳项已落到拓扑、接口、代码顺序、Redis、DDL 与验收闸口中。实现过程中的偏差只允许走"发现 → 登记为已知问题 → 下一小版本修订"流程，不在冻结基线上原地漂移。

**复核重点**：① §3.3 公钥白名单与密钥轮换流程（HTTP 下安全性的支点，实现最易走样成"自动信任 JWKS"）；② §四 exchange 的七步顺序与 §五 user_status_event 同事务写入（两个"漏一个就出事故"的点）；③ M2 的 ⑦⑧ 与 M5a 的 ③④（新增四条测试是本版补偿层是否真正生效的唯一判据）。
