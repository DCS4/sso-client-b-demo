# SSO V2 协议摘要

本文供运行 B Demo 时快速查阅。权威契约位于 `DCS4/yth` 的 `docs/SSO-V2接口文档.md`，服务端与示例均不兼容旧 `/sso/v1` 协议。

## 公共前缀

```text
http://SSO-IP:10089/oauth2Server/oauth2
```

| 方法 | 路径 | 调用方 |
| --- | --- | --- |
| GET | `/authorize` | 浏览器顶层导航 |
| POST form | `/token` | 外部系统后端 |
| POST JSON | `/checkAccessToken` | 外部系统后端 |
| POST form | `/getUserInfoByOauth2` | 外部系统后端 |
| POST JSON | `/logout` | 外部系统后端 |

除 `/authorize` 外，不允许浏览器或 Vue 直接调用 SSO。

## 授权

```http
GET /authorize?response_type=code
  &client_id=client_xxx
  &redirect_uri=http%3A%2F%2FB-IP%3A18080%2Fapi%2Fauth%2Fcallback
  &state=RANDOM_STATE
  &page_code=B_PAGE_01
```

`PAGE_CONTROLLED` 必须传 `page_code`；`SSO_ONLY` 可以省略。成功只返回已经登记的固定回调：

```text
/api/auth/callback?code=ONE_TIME_CODE&state=RANDOM_STATE
```

授权码默认 60 秒有效且只能消费一次。客户端和回调无效时 SSO 直接返回 400，不向未登记地址跳转。

## 授权码兑换

```http
POST /token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
client_id=client_xxx
client_secret=...
code=...
redirect_uri=http://B-IP:18080/api/auth/callback
```

```json
{
  "code": 200,
  "success": true,
  "message": "",
  "result": {
    "access_token": "...",
    "refresh_token": "...",
    "token_type": "Bearer",
    "expires_in": 7200,
    "refresh_expires_in": 14400,
    "client_id": "client_xxx",
    "scope": "page:B_PAGE_01",
    "openid": "user-id",
    "page_code": "B_PAGE_01"
  }
}
```

## 刷新

仍调用 `/token`：

```text
grant_type=refresh_token
client_id=client_xxx
client_secret=...
refresh_token=...
```

成功后 AccessToken 和 RefreshToken 都会变化。旧 RefreshToken 立即失效，重放旧值会撤销整个 Token family；客户端必须串行刷新并原子替换 Token 对。

## 页面校验

```http
POST /checkAccessToken
Content-Type: application/json

{
  "clientId": "client_xxx",
  "clientSecret": "...",
  "accessToken": "...",
  "pageCode": "B_PAGE_01"
}
```

只在 `success == true && result.active == true` 时放行。无效 Token 通常返回 HTTP 200、业务 `code=500`、`success=false`、`result.active=false`，不会区分不存在、过期、撤销或权限不足。

## 用户信息

```http
POST /getUserInfoByOauth2
Content-Type: application/x-www-form-urlencoded

client_id=client_xxx
client_secret=...
tokenValue=...
```

当前只返回 `id`、`userCode`、`name`、`companyCode`、`enableStatus`，不返回任何密码字段。

## 注销

```http
POST /logout
Content-Type: application/json

{
  "clientId": "client_xxx",
  "clientSecret": "...",
  "accessToken": "..."
}
```

该接口只撤销当前页面授权对应的 Token family，不注销 Portal 全局 SID。外部系统可以逐个注销自己保存的页面 Token；Portal 全局退出后，其它系统会在下一次页面校验时发现 SID 已失效。

## 错误原则

- HTTP 401：客户端或密钥错误，停止重试并联系管理员；
- HTTP 400：一次性凭证或请求错误，重新开始授权；
- HTTP 5xx/网络错误：受控页面 fail closed，不降级放行；
- 回调 `access_denied`：显示无权限，不循环跳 SSO；
- RefreshToken 返回 HTTP/业务 `code=400`：清本地页面 Token，重新授权；
- 日志不得记录 secret、code、Token、Cookie 或完整回调查询串。
