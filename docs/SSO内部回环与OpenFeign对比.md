# SSO 内部接口：回环直连 vs OpenFeign 对比

> 背景：`/internal/sso/**`（beginSession / revokeUserSessions / logout）当前为同机回环 RestTemplate 直连（`PortalSsoInnerClient`，77 行，2s/2s 超时 + `X-INNER-TOKEN`），设计文档 V1.2 冻结稿 §2.2 明确"固定同机回环，不走 Feign/Nacos"。本文评估改为 OpenFeign 的得失。

## 一、结论（先行）

- OpenFeign **确实是本仓库的框架惯例**（`jeecg-system-cloud-api` 里 11+ 个 `@FeignClient(contextId, value, fallbackFactory, configuration=FeignConfig.class)`），从可读性、可测性、降级模式化角度更贴合 JeecgBoot 体系。
- 但直接切 Feign 与这条链路最核心的安全决策——**SSO 只绑 `127.0.0.1`、`/internal/**` 物理隔离**——存在正面冲突。
- 推荐：**路径 A**（先抽 Java 接口、传输可换，V1 维持回环；V2 再换 Feign 实现）。

## 二、现状对比（基于本仓库实际代码）

| 维度 | 内部回环（现状 `PortalSsoInnerClient`） | OpenFeign（仓库惯例） |
|---|---|---|
| 形态 | 手写 RestTemplate，2s/2s 超时，`Map<String,String>` 手工拼 body | 声明式接口 + `fallbackFactory` + 统一 `FeignConfig` |
| 寻址 | 配置写死 `http://127.0.0.1:19089` | Nacos 服务发现，按服务名路由 |
| 降级 | 调用方 try-catch（登录钩子现状即如此） | `fallbackFactory` 统一兜底，模式化 |
| 熔断/链路 | 无（直连，2 秒超时够用） | 天然接 Sentinel、Skywalking |
| 可测性 | 具体类，mock 要造对象 | 接口，单测直接 mock |
| 团队认知 | 与仓库 90% 跨服务调用写法不一致 | 与 `SystemDictAPI`、`DataSyncApi` 等完全同构 |
| 开销 | 零额外依赖 | 每次调用多一层代理 + LB 上下文（仅 3 个调用，可忽略） |

## 三、两个硬冲突

**冲突 1：服务发现和回环绑定互斥。**
SSO 出于安全只绑 `127.0.0.1:19089`。Feign 从 Nacos 拿到的实例地址必须"调用方能到达"：
- SSO 把 `127.0.0.1:19089` 注册进 Nacos → 只有同机调用方碰巧能用（冻结稿原话："仅单机拓扑碰巧可用，语义混淆"）；
- SSO 改注册可达地址（主机 IP）→ 必须新开非回环监听端口，`/internal/**` 物理隔离失效，只能靠 ACL/网段策略补，攻击面变大。

**冲突 2：注册 Nacos = `/internal/**` 对所有服务可发现。**
现在"谁能调内部接口"由**网络层**决定（只有本机能到 127.0.0.1）+ token。SSO 一旦进 Nacos，任何服务写个 `@FeignClient` 就能发现并调用 `beginSession`（给任意 sub 建会话）——隔离从"物理边界"退化成"团队约定"。

另注：Feign 本身**不提供鉴权**，`X-INNER-TOKEN` 仍需 `RequestInterceptor` 注入，信任模型不变，只换信封。

## 四、两条可行路径

### 路径 A（推荐，不破坏冻结基线）：接口抽象 + 传输可换

把 `PortalSsoInnerClient` 的三个方法抽成 Java 接口（如 `ISsoInnerApi`，放 `jeecg-system-cloud-api` 或 sso 包），现有回环 RestTemplate 作为 V1 实现；`UserStatusOutboxService` 同理。调用方只依赖接口——单测可 mock、写法上已"像 Feign"。V2（HTTPS 升级/拓扑变化、SSO 有可达内网地址或多实例）时补 `@FeignClient` 实现 + `fallbackFactory` + Sentinel 无缝替换。**安全性零变化，冻结声明不打破。**

### 路径 B（现在就切 Feign）：需成套改动，走 V1.3 修订

1. SSO 注册 Nacos 并暴露可达地址（第二监听端口 + 网段 ACL，`/internal` 永不进 Gateway）；
2. Feign `RequestInterceptor` 注入 `X-INNER-TOKEN`；
3. 每个 client 配 `fallbackFactory`（降级语义与现在调用方 catch 对齐）；
4. 设计文档 §2.2 按冻结流程修订为 V1.3，风险登记补"Nacos 暴露内部接口面"条目。

代价：安全边界从网络层挪到配置层；V1 阶段收益只有"写法统一"。

## 五、问答：如果写成 OpenFeign，加 token 鉴权是否可以？

**答：可以，而且是必须的；但要有三个配套条件。**

1. **技术上完全可行，服务端零改动。** Feign `RequestInterceptor` 给每个请求加 `X-INNER-TOKEN` 头，服务端 `InnerAuthFilter` 校验逻辑原样复用。信任模型不变：Feign 只换寻址和调用信封，不提供鉴权。区别在于——今天是"网络回环（第一道闸）+ token（第二道闸）"，切 Feign 后网络闸消失（实例地址对集群可达），**token 从第二道闸升格为唯一应用层闸**。

2. **三个配套条件（缺一不可）：**
   - **密钥管理**：token 走环境变量/受控密钥（现状已是 `${SSO_INNER_TOKEN}`），禁止明文进代码或 Nacos 配置；V1 是 HTTP 内网，头本身可被链路截获，须支持轮换。
   - **分发范围 / 分调用方 token**：Feign client 若放 `jeecg-system-cloud-api`，所有依赖该 jar 的服务都获得调用能力——"持有 client + 知道 token"即信任边界。建议门户/System **分调用方各自 token**，或 client 不放通用 api jar；SSO 侧可再加源 IP 白名单做第二道防线。
   - **`/internal/**` 永不进 Gateway**：Feign 走 Nacos 直连实例本就不经网关，但仍须确保网关路由与 Nginx 均不暴露 `/internal/**`（现状 Nginx 404 规则保留）。

3. **参考实现骨架（V2 用）：**

```java
@Configuration
public class SsoInnerFeignConfig implements Feign.RequestInterceptor {
    @Value("${sso.portal.inner-token:}") private String innerToken;
    @Override
    public void apply(RequestTemplate template) {
        template.header("X-INNER-TOKEN", innerToken);
    }
}

@FeignClient(contextId = "ssoInnerApi", name = "jeecg-sso", path = "/internal/sso",
        configuration = SsoInnerFeignConfig.class,
        fallbackFactory = SsoInnerApiFallbackFactory.class)
public interface ISsoInnerApi {
    @PostMapping("/beginSession") BeginSessionResult beginSession(@RequestBody BeginSessionRequest req);
    @PostMapping("/logout")       String logout(@RequestBody Map<String, String> req);
    @PostMapping("/revokeUserSessions") Map<String, Object> revokeUserSessions(@RequestBody RevokeUserRequest req);
}
```

4. **结论**：token 鉴权在应用层是充分的（与现行 `InnerAuthFilter` 强度等价）。真正的决策点不在"能不能加 token"，而在"**SSO 内部地址是否注册进 Nacos / 监听是否可达**"——它决定 token 是"第二道闸"还是"唯一一道闸"。走路径 B 时上述三条必须全做，并按冻结流程登记风险。

## 六、建议

V1 维持回环（它是这条链路安全模型的承重墙）；采纳"贴合框架"诉求做路径 A——现在小成本抽接口，V2 直接换 Feign 实现（届时 token 鉴权按第五节三条落地）。
