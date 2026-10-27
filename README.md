# SSO V2 外部系统接入示例

这个项目演示：**怎样让一个已有业务系统接入 DCS4/yth SSO V2**。示例中的“B 系统”就是接入方自己的系统；Portal 提供统一登录界面，SSO 服务负责签发和校验凭据。

技术栈为 Spring Boot 2.7.18 / JDK 8 + Vue 3，通过 HTTP 调用远端 SSO。项目不包含 SSO 服务端、账号密码登录页或权限管理后台，也不需要自己的数据库。运行完整登录流程前，需要已有的 SSO 环境和登记好的客户端。

## 从哪里开始

| 你想做什么 | 阅读入口 |
| --- | --- |
| 第一次运行、配置地址、排查启动问题 | [新手操作手册](docs/新手操作手册.md) |
| 了解所有配置项和地址的对应关系、密钥全生命周期 | [配置说明](docs/配置说明.md) |
| 分清普通单点登录和页面受控 | [认证模式详解](docs/认证模式详解.md) |
| 先接入普通单点登录 | [普通单点登录教程](docs/tutorials/普通单点登录教程.md) |
| 由 SSO 决定每个页面的访问权限 | [页面受控教程](docs/tutorials/页面受控教程.md) |
| 新增业务页面并保护其数据接口 | [新增页面与 API 教程](docs/tutorials/新增页面与API教程.md) |
| 前端需要哪些接口、怎样发起登录和退出 | [前端接入说明](frontend/README.md) |
| 将示例迁入已有业务系统 | [外部系统接入指南](docs/外部系统接入指南.md) |
| 查协议字段、验收步骤和耗时日志 | [SSO协议摘要](docs/SSO协议摘要.md)、[验证与验收说明](docs/验证与验收说明.md) |

## 工程目录

```text
sso-client-demo/
├── README.md
├── .env.example                         # 当前环境配置模板，真实值填写到 .env
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/dcs4/ssoclient/
│       │   │   ├── DemoApplication.java  # 启动入口
│       │   │   ├── config/              # SsoConfig、SecurityConfig、JSON 错误处理
│       │   │   ├── sso/                 # SsoGateway、SsoClient、协议数据模型
│       │   │   ├── auth/                # 本地授权流程、固定回调、state 和 Token
│       │   │   └── demo/                # 首页展示接口、页面 Controller、HTML 视图
│       │   └── resources/
│       │       ├── application.yml      # 唯一的默认配置文件
│       │       └── logback-spring.xml
│       └── test/java/com/dcs4/ssoclient/ # 本地接口、授权流程与协议响应测试
├── frontend/
│   ├── src/
│   │   ├── main.ts                      # 挂载 Vue
│   │   ├── App.vue                      # 本地会话摘要和三个页面链接
│   │   └── api.ts                       # 调用 B 接口及添加 CSRF 请求头
│   └── vite.config.ts                   # 开发时代理 /api 和 /pages
├── docs/
│   ├── 新手操作手册.md
│   ├── 配置说明.md
│   ├── 认证模式详解.md
│   ├── 外部系统接入指南.md
│   ├── SSO协议摘要.md
│   ├── 验证与验收说明.md
│   ├── tutorials/                       # 两种模式及扩展示例的操作步骤
│   │   ├── 普通单点登录教程.md
│   │   ├── 页面受控教程.md
│   │   └── 新增页面与API教程.md
│   └── examples/                        # 可复制的教程代码，默认不参与应用构建
├── scripts/                             # 构建前端、拷贝静态文件、打包后端
└── sql/README.md                         # 数据库与客户端登记说明（接入方无须自建表）
```

阅读代码时，先从 `demo/PageController.page()` 看到 `auth/PageAccessService.enter()`，再看 `auth/AuthController.callback()` 和 `completeCallback()`，最后看 `sso/SsoClient` 如何发送 HTTP 请求。`PageCatalog` 位于 `auth/`，负责页面映射；其中的三条记录是演示数据。

`sso/`、`auth/` 和必要配置是接入参考，`demo/`、Vue 首页和 `PageView` 是展示示例。核心流程不依赖演示界面。具体移植边界见接入指南。

## 快速运行

准备好 JDK 8、可执行的 Maven、符合 `frontend/package.json` 要求的 Node.js，以及 SSO 管理员提供的配置。首次在仓库根目录执行：

```powershell
# 仅第一次复制；已有 .env 时直接编辑，避免覆盖真实配置。
Copy-Item .env.example .env
# 编辑 .env：填写 SSO 地址、client_id、client_secret、回调和认证模式。
.\scripts\package.ps1
java -jar backend/target/sso-client-b-demo-0.1.0-SNAPSHOT.jar
```

Linux/macOS 使用 `cp .env.example .env`、`bash scripts/package.sh`，随后执行同一个 `java -jar` 命令。默认打开 [B 系统首页](http://localhost:18085)，点击“订单查询”开始授权。SSO 管理端的系统地址登记为 `http://localhost:18085`，回调路径为 `/api/auth/callback`。

首次配置、非本机访问以及前后端双进程开发的完整步骤见[新手操作手册](docs/新手操作手册.md)。本地端口只是示例，SSO 地址和回调必须使用当前环境实际可访问的地址。

## 两种模式共用一个工程

| 行为 | SSO_ONLY 普通单点登录 | PAGE_CONTROLLED 页面受控（默认） |
| --- | --- | --- |
| SSO 判断什么 | 用户身份 | 用户身份及页面权限 |
| 向 SSO 发送页面编码 | 不发送 | 发送当前 page_code |
| 本地 Token 保存 | 会话共用一组 | 每个页面分别保存 |
| 本示例每次访问页面 | 后端向 SSO 校验 | 后端向 SSO 校验 |
| 业务数据、操作和租户权限 | 接入方自行实现 | 接入方按业务需要继续实现 |

在 `.env` 中设置 `SSO_AUTH_MODE`，并让 SSO 管理端的客户端模式保持一致。共用登录、回调、刷新和退出流程；模式差异由配置判断及 Token 键映射处理，无需复制两套工程。切换步骤见[认证模式详解](docs/认证模式详解.md)。

## 示例范围

示例使用单实例 HttpSession，固定一个后端回调，三个演示页面共用公共守卫。SSO Token 和 client_secret 保存在后端；浏览器通过自动管理的本地会话 Cookie 关联后端状态，Vue 不保存 SSO Token。首页的用户信息是本地摘要，进入页面时仍会实时校验。

它提供的是接入参考代码。多实例共享会话、业务用户映射和业务权限需适配接入方现有系统。Spring Boot 2.7.18 用于保留 JDK 8 兼容性。远端协议的权威契约由 SSO 服务端仓库 `DCS4/yth/docs/SSO-V2接口文档.md` 维护；本仓库提供客户端使用到的摘要。
