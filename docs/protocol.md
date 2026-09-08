# 实际协议与联调说明

对照 DCS4/yth `codex/sso-integration` 的 SsoProtocolController、SsoDtos、HmacVerifier、TokenService、LogoutOutboxService 实现。该服务是自定义身份断言协议，不是可直接配置 oauth2Login 的完整 OIDC Provider。

## 请求

HMAC 原文（LF 换行，无结尾换行）：POST、实际 URI 路径、client_id、秒级 timestamp、随机 nonce、原始 UTF-8 JSON body 的小写 SHA256 十六进制；依次用换行连接。HmacSHA256 使用 UTF-8 Secret，结果 Base64URL 无 padding。请求字节只序列化一次。

头：X-SSO-Client / X-SSO-Timestamp / X-SSO-Nonce / X-SSO-Signature。

| 接口 | 内容 |
|---|---|
| GET /authorize | client_id, redirect_uri, state, code_challenge, code_challenge_method=S256 |
| POST /exchange | code, redirect_uri, code_verifier；返回 identity_assertion |
| POST /session/status | sid；返回 session_status JWS |
| POST /logout | sid, post_logout_redirect_uri；返回 logout_request |
| GET /logout | request=一次性 logout_request，通过浏览器跳转清 SSO Cookie |
| B POST /api/sso/backchannel-logout | **JSON** {"logout_token":"JWS"}，成功返回 204 |

session_expires_at、auth_time、timestamp 都是 Unix 秒；JWT iat/exp 由库转成 Date。状态断言须验证 client_id、sid、request_nonce、active；不能只读取 JSON active。

## 网络

浏览器必须能访问 Portal、SSO 和 callback；SSO 必须能访问 B backchannel 地址。跨主机不要登记 localhost。按实际 B 内网 IP 配置 SSO 回调 CIDR 白名单，不使用 /0 放开。B 不连接 SSO Redis/MySQL，不共享 SSO_SID。

同主机不同端口的 Cookie 不按端口隔离。两个示例分别设置 `--server.servlet.session.cookie.name=B_DEMO_SESSION` 与 `C_DEMO_SESSION`，不同主机部署更接近真实环境。

## 公钥与故障

可信公钥文件代替在线 JWKS 指纹白名单：文件内容本身就是信任锚。未知 kid、非 RS256、私钥文件、签名错误均拒绝。不要从未验证的 HTTP JWKS 下载后直接当可信文件。

登出通知按 SID 幂等，重复有效通知返回 204。记录 24 小时 SID 墓碑防止登出先于登录回调到达。内存模式仅用于单进程参考；分布式持久化需统一原子边界。

回调交易只允许一个未完成登录；同浏览器再次点击登录替换前一个 state，旧回调会拒绝。失败应手动重新登录。全局退出先销毁本地会话，再请求 SSO，失败不会声称全局成功。

## 文档来源

- https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/getting-started.html
- https://www.rfc-editor.org/rfc/rfc7636.html
- https://openid.net/specs/openid-connect-backchannel-1_0.html
