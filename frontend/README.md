# 前端接入说明

Vue 首页只展示本地用户摘要、演示页面目录和退出结果。SSO 的授权码兑换、Token 刷新、远端校验都在 B 后端完成；前端不需要 SSO SDK，也不保存 SSO Token 或 client_secret。

## 1. 文件职责

| 文件 | 职责 |
| --- | --- |
| `src/main.ts` | 创建 Vue 应用并挂载到首页 |
| `src/App.vue` | 初始化目录和本地用户状态，展示页面链接及退出按钮 |
| `src/api.ts` | 调用 B 的同源 JSON 接口；POST 前获取 CSRF Token |
| `vite.config.ts` | 开发时将 `/api`、`/pages` 代理到后端 18085 |

项目没有使用 Vue Router。三个业务页面是 B 后端返回的 HTML，用于让读者直接观察页面守卫；它们不是 Vue 组件。

## 2. 前端实际使用哪些接口

以下路径都属于 B 系统，不是远端 SSO 接口。前端本地接口直接返回对象，没有 SSO 协议的 success/code/result 外壳。

| 方法和路径 | 如何调用 | 当前首页是否使用 |
| --- | --- | --- |
| `GET /api/pages` | fetch 获取演示目录 | 是 |
| `GET /api/auth/me` | fetch 读取本地会话摘要 | 是 |
| `GET /api/csrf` | POST 前 fetch 获取本地 CSRF Token | 是 |
| `POST /api/auth/logout/system` | 带 CSRF 请求头，退出 B | 是 |
| `POST /api/auth/logout/page/{pageCode}` | 带 CSRF 请求头，清理相应授权 | 后端已提供，首页无按钮 |
| `GET /pages/orders` 等 | 普通链接或 window.location.assign，整页导航 | 是 |
| `GET /api/auth/callback` | 由 SSO 通过浏览器回跳 | 前端代码不主动 fetch |

### 页面目录

`GET /api/pages` 对匿名用户也开放，返回全部演示记录，例如：

```json
[
  {"code":"B_PAGE_01","name":"订单查询","path":"/pages/orders"},
  {"code":"B_PAGE_02","name":"经营报表","path":"/pages/reports"},
  {"code":"B_PAGE_03","name":"运维控制台","path":"/pages/operations"}
]
```

它不表示当前用户拥有这些权限。真正点击链接后，B 后端才检查是否允许访问。

### 本地用户摘要

`GET /api/auth/me` 没有本地用户时返回 **401，响应体为空**；有本地用户时返回：

```json
{
  "id":"示例用户ID",
  "userCode":"demo",
  "name":"演示用户",
  "companyCode":"示例组织",
  "authMode":"PAGE_CONTROLLED",
  "authorizedTokenCount":1
}
```

`authMode` 是后端当前模式；`authorizedTokenCount` 是本地保存的 Token 数。这个接口不执行远端实时校验，因此 Portal 已退出或页面权限发生变化时，它仍可能显示缓存的用户。业务授权请使用页面守卫或受保护的数据接口。

## 3. 初始化与登录怎样处理

`App.vue` 挂载后调用 `refresh()`：先取页面目录，再取本地用户。首次 `/me` 的 401 是正常未登录状态，不需要立即弹错误或自动循环登录。

当前简单实现把 `/me` 请求的其他失败也显示为未登录；`/api/pages` 加载失败会使初始化中断。排查网络故障时先看 Network，不能仅凭首页文案判断。项目没有增加全局重试或错误状态管理。

登录从业务链接开始：

```html
<a href="/pages/orders">订单查询</a>
```

也可以使用：

```ts
window.location.assign('/pages/orders')
```

B 返回 302 时，浏览器整页进入 SSO；SSO 回调 B 后，B 返回 303 再进入原页面。Vue 不解析 code/state，不拼接授权 URL，也不替后端兑换 code。

不要用 `fetch('/pages/orders')` 代替登录导航：fetch 跟随重定向属于请求过程，不会自动改变当前页面，跳到远端 SSO 时还可能遇到跨域限制。

## 4. POST、Cookie 和 CSRF 怎样处理

浏览器通过同源请求自动携带 `B_DEMO_SESSION` Cookie，JavaScript 无需读取 HttpOnly Cookie。CSRF Token 是本地 POST 校验凭据，可以由前端取到；它不是 SSO AccessToken。

`api.ts` 的 `request()` 保持这个顺序：

1. POST 前 GET `/api/csrf`，响应形如 `{"token":"本地CSRF值"}`。
2. 在同一浏览器会话下设置 `X-CSRF-TOKEN` 请求头。
3. 调用目标 POST；先读取响应文本，以兼容 401 等空响应；非空时尝试解析 JSON，Spring 默认错误页不是 JSON 时改用 HTTP 状态提示。
4. 非 2xx 响应抛出错误，优先使用响应里的 message。

可以直接复用现有封装：

```ts
import { request } from './api'

const result = await request('/api/auth/logout/system', 'POST')
console.log(result.message)
```

本示例默认同源部署。双进程开发通过 Vite 代理维持浏览器同源访问，不需要额外写跨域 Cookie 或 CORS 代码。

## 5. 退出接口返回什么

系统退出成功响应示例：

```json
{
  "message":"已退出 B 系统；Portal 全局会话未被注销",
  "attempted":2,
  "confirmed":2
}
```

`attempted` 是尝试注销的不同 Token 数，`confirmed` 是 SSO 确认撤销的数量。即使远端未全部确认，本地 Session 也会被销毁；前端在 finally 中刷新本地状态。已有 Portal 会话可能使下次授权静默完成。

单页退出调用示例：

```ts
await request('/api/auth/logout/page/B_PAGE_01', 'POST')
```

正常响应为 `{"message":"本地页面授权已清除","ssoRevoked":true}`；false 表示没有取得远端撤销确认，例如本地原本没有相应 Token。通信异常可能返回 400/503，表示本地已清理但远端未确认。

PAGE_CONTROLLED 清理指定页面；SSO_ONLY 清理共享系统 Token，影响全部页面后续访问。单页退出不会清除本地用户摘要，不能靠 user 是否非空判断页面许可。

## 6. 失败时前端会看到什么

| 情况 | 响应或现象 | 怎样处理 |
| --- | --- | --- |
| 没有本地用户 | `/api/auth/me` 返回 401 空响应 | 首页显示尚未建立本地会话 |
| 固定回调的 state 无效 | 400 JSON，包含 message | 从业务页重新开始，避免重复打开旧回调 |
| 回调收到 access_denied | 403 JSON，包含 message | 提示管理员检查页面授权，不自动循环跳转 |
| 本地 POST 缺少 CSRF | Spring Security 返回 403 | 检查 CSRF 获取和同会话请求头；响应可能不含自定义 JSON |
| SSO 不可用 | 相关后端操作返回 503 | 展示失败信息或查看日志，受保护页面不会放行 |
| 普通 HTML 页面校验失败 | 由 Spring Boot 的页面错误处理响应 | 不假定它与 JSON API 有相同响应结构 |

本地 RestController 的异常由 `config/ApiExceptionHandler` 保持为 message JSON。首页的操作记录只记录当前 Vue 页面内的操作，主要是退出结果；整页导航后会重新初始化，不是完整的 SSO 链路日志。

## 7. 两种模式下前端需要改什么

登录、链接、CSRF 和退出调用方式相同。Vue 通过 `/me` 的 authMode 解释当前行为，不向后端传入认证模式，也没有运行时切换开关。未登录时首页使用中性说明，不根据缺少用户信息猜测模式。

SSO_ONLY 共用系统 Token，身份通过后由业务系统实施自己的权限；PAGE_CONTROLLED 使用页面 Token。完整差异见[认证模式详解](../docs/认证模式详解.md)。

## 8. 换成实际 Vue / React 路由

可以保留顶层导航到后端受保护入口的方式。对于需要先获取数据的 SPA，后端 API 应返回可供前端识别的未授权状态，再由前端发起顶层导航；不能只隐藏菜单或保护路由组件。

本项目没有默认提供 `/api/page-access` 接口。可复制的业务 API 示例及 401 + loginUrl 处理见[新增页面与 API 教程](../docs/tutorials/新增页面与API教程.md)。

## 9. 开发和构建

```bash
npm ci
npm run dev
```

开发入口固定为 `http://localhost:5173`；`/api` 和 `/pages` 代理到 `http://localhost:18085`。这些地址按本机浏览器演示。若从其他机器访问 Vite，启动时增加 `--host 0.0.0.0`，并将系统登记和回调统一改为浏览器实际访问的 IP 或域名。需要先启动后端，并按[新手操作手册的双进程步骤](../docs/新手操作手册.md#8-修改前端时双进程开发)同步登记回调。

`npm run build` 执行类型检查和构建。根目录 `scripts/package.*` 会把 dist 复制到后端静态目录再打包 JAR；仅前端 build 不会自动更新后端静态文件。`npm run preview` 不包含开发代理，仅用于查看前端产物。
