# 教程三：新增页面并保护业务 API

目标：新增“库存查询”页面 `/pages/inventory`，再为它提供受保护的数据接口。先完成任一模式的运行教程。

默认项目只有三个页面。本教程的第四个页面和 API **需要按步骤添加后才会生效**；`docs/examples/` 不在 Maven 应用源码目录中，不会自动注册接口。

## 1. 在 B 登记本地页面

编辑 `backend/src/main/java/com/dcs4/ssoclient/auth/PageCatalog.java`，在构造方法中现有三个 register 后添加：

```java
register(new Page("B_PAGE_04", "库存查询", "/pages/inventory"));
```

页面路径、编码和名称由后端决定。`/api/pages` 会自动包含新记录，Vue 首页随之显示链接，不需要再维护第二份前端菜单数组。

## 2. 让页面请求进入后端守卫

编辑 `backend/src/main/java/com/dcs4/ssoclient/demo/PageController.java`，把映射改为：

```java
@GetMapping({"/pages/orders", "/pages/reports", "/pages/operations", "/pages/inventory"})
```

方法体无需改动：它从请求路径找到 Page，调用 `PageAccessService.enter()`，需要授权时返回 302，通过时交给 `PageView` 显示页面。不要只添加前端链接而忘记真实后端入口。

在已有业务系统中，可让库存 Controller 执行同样的守卫调用，通过后再返回自己的业务页面，不需要复制演示 HTML。

## 3. 根据模式准备远端信息

- **PAGE_CONTROLLED**：在 SSO 登记 B_PAGE_04、`/pages/inventory` 和示例权限 `b:inventory:view`，给测试用户分配权限。其他页面的 Token 不能作为此页的授权。
- **SSO_ONLY**：不需要为该页增加 SSO 页面登记，继续复用系统 Token；库存的业务权限由 B 自行实现。

不需要新增回调地址、client_id 或 client_secret。所有页面仍共用原固定回调。

## 4. 先验证页面

重启后端；单 JAR 运行时重新打包，双进程运行时重启后端即可。刷新首页后应出现“库存查询”。分别点击链接和直接输入 `/pages/inventory`，确认两种进入方式都经过校验。

页面受控模式还应使用没有库存权限的账号确认拒绝结果。

## 5. 新增受保护的数据接口

把[完整 InventoryApiController.java](../examples/InventoryApiController.java)复制到：

```text
backend/src/main/java/com/dcs4/ssoclient/demo/InventoryApiController.java
```

重启后端后，`GET /api/demo/inventory` 生效。示例直接复用当前 PageAccessService：

```java
AccessResult decision = access.enter(request, pages.requireByPath("/pages/inventory"));
```

校验通过时返回固定演示数据：

```json
{"items":[{"sku":"DEMO-001"}]}
```

需要授权时返回 HTTP 401：

```json
{"message":"请先完成本次授权","loginUrl":"由后端生成的SSO授权地址"}
```

loginUrl 来自后端公共流程生成的授权地址，不由浏览器自行提交或拼接。当前请求已经在后端保存了 state 和回跳目标，前端直接导航该地址即可。

如果远端校验失败或服务不可用，沿用现有的异常处理，可能返回其他错误状态，不会返回库存数据。`SecurityConfig` 的 permitAll 并不自动保护新 API，显式调用守卫是这个教学例子的关键。

## 6. 前端怎样处理 API

下面代码是新增接口的调用示例，不是默认首页已实现的功能：

```ts
async function loadInventory() {
  const response = await fetch('/api/demo/inventory')
  const data = await response.json()
  if (response.status === 401 && data.loginUrl) {
    window.location.assign(data.loginUrl)
    return
  }
  if (!response.ok) throw new Error(data.message || `请求失败 (${response.status})`)
  return data.items
}
```

为什么使用 401 + loginUrl：fetch 不能替用户完成整页登录导航。前端收到明确状态后再跳转，避免把 JSON 请求直接重定向到远端网页。

回调后返回的是 state 记录的库存页 `/pages/inventory`，不会自动恢复上一次 JavaScript 调用。实际 SPA 可将该路径设计为受后端入口保护的业务路由，并在页面重新加载后请求数据。

## 7. 明确业务权限边界

这个 API 只返回固定演示数据。接入真实库存查询时，应在守卫通过后加入原系统的库存权限、组织或租户范围检查，再执行查询。SSO_ONLY 确认身份，PAGE_CONTROLLED 额外确认页面权限，两者都不能自动代替具体数据规则。

原示例的登录、固定回调、刷新和退出代码无需为这个页面复制一份。
