# SSO 协议摘要

本文供接入方查阅平台认证服务的接口。完整契约以平台管理员提供的《SSO 接口文档》为准。旧 `/sso/v1` 协议已停止兼容。

客户端前端调用本地后端的接口见[示例前端调用参考](示例前端调用参考.md)。

## 公共前缀

```text
http://SSO-IP:19089/oauth2Server/oauth2
```

| 方法 | 路径 | 调用方 |
| --- | --- | --- |
| GET | `/authorize` | 浏览器顶层导航 |
| POST form | `/token` | 外部系统后端 |
| POST JSON | `/checkAccessToken` | 外部系统后端 |
| POST form | `/getUserInfoByOauth2` | 外部系统后端 |
| POST JSON | `/logout` | 外部系统后端（撤销单页/本系统 Token family） |
| GET | `/logout` | 浏览器顶层导航（客户端发起全局单点退出） |

除 `/authorize` 和 `GET /logout`（浏览器顶层跳转）外，不允许浏览器或 Vue 直接调用 SSO。

## 授权

```http
GET /authorize?response_type=code
  &client_id=client_xxx
  &redirect_uri=http%3A%2F%2FB-IP%3A18085%2Fapi%2Fauth%2Fcallback
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
redirect_uri=http://B-IP:18085/api/auth/callback
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

刷新结果的绑定异常、刷新请求通信异常或刷新后的新 Token 校验通信异常时，示例会清除该组本地 Token。通信异常的当前请求返回 503；绑定异常返回协议错误。下一次请求重新开始授权，不重用结果不确定的旧 RefreshToken。

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

普通单点登录省略 `pageCode`。客户端只有在响应成功、`result.active == true`，并核对响应所属客户端、用户和授权范围后，才执行相应的本地权限检查。页面受控时，还需确认返回的页面编码与当前请求一致。无效 Token 通常返回 HTTP 200、业务 `code=500`、`success=false`、`result.active=false`，不会区分不存在、过期、撤销或权限不足。

## 用户信息

```http
POST /getUserInfoByOauth2
Content-Type: application/x-www-form-urlencoded

client_id=client_xxx
client_secret=...
tokenValue=...
```

基础用户资料位于 result 对象，包含 id（平台用户编号）、userCode（用户编码）、name（姓名）、companyCode（单位编码）和 enableStatus（启用状态）。

普通单点登录客户端启用外部账号必绑后，result 中还包含 externalUserId（客户端系统中的本地用户编号，字符串）。缺少有效绑定时平台拒绝当前客户端授权。参见[平台维护外部账号绑定接入指南](../外部系统接入/普通单点登录接入指南（平台维护外部账号绑定）.md)。

## 注销与全局单点退出

SSO 区分**页面 Token 注销（仅退当前客户端）**与**客户端全局单点退出（退出整个统一认证服务）**：

### 1. 页面 Token 注销（仅退出当前系统）

```http
POST /logout
Content-Type: application/json

{
  "clientId": "client_xxx",
  "clientSecret": "...",
  "accessToken": "..."
}
```

该接口只撤销当前页面授权对应的 Token family，**不注销** Portal 全局 SID。外部系统在用户点击“退出 B 系统”时逐个注销自己保存的页面 Token 并销毁本地会话；由于全局 SID 仍在，再次访问业务页面时可能静默免密完成授权。

### 2. 客户端全局单点退出（退出整个服务 / RP-Initiated Single Logout）

```http
GET /logout?client_id=client_xxx
  &post_logout_redirect_uri=http%3A%2F%2FB-IP%3A18085%2F
```

当用户点击“退出整个服务”时，外部系统先清理本地 Token 与本地会话，再引导浏览器顶层跳转到该地址：
- SSO 校验 `client_id` 已启用，且 `post_logout_redirect_uri` 与管理端登记的 `sysAddress` 严格同源（协议、主机、端口一致）；
- SSO 从浏览器请求读取 `SSO_SID` Cookie，销毁 Redis 中的全局根会话，并清理该客户端下关联的全部 Token family；
- SSO 下发 `Set-Cookie: SSO_SID=; Max-Age=0` 清除浏览器 Cookie，并 302 重定向回 `post_logout_redirect_uri`；
- 再次点击任何受保护业务页面时，不再免密静默放行，必须重新进入统一登录页输入账号密码后再授权。

## 错误原则

- 平台接口返回 HTTP 401 时，先看接口和响应提示。客户端认证失败时检查编号和密钥。用户信息接口返回令牌无效或用户停用时，检查当前授权与账号状态。
- HTTP 400：一次性凭证或请求错误，重新开始授权；
- 平台返回 HTTP 5xx 或发生网络异常时，两种模式都应停止当前业务请求，并提示用户稍后重试。
- 回调 `access_denied`：显示无权限，不循环跳 SSO；
- RefreshToken 返回 HTTP/业务 `code=400`：清本地页面 Token，重新授权；
- 日志不得记录 secret、code、Token、Cookie 或完整回调查询串。
