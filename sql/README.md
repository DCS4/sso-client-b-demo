# 数据库与联调脚本说明

## 1. 接入方（B 系统）不需要数据库

示例把用户信息和页面 Token 放在服务端 `HttpSession`，自身无需建表。真实系统可存入已有数据库或 Redis，但**不得保存或返回 SSO 用户的密码**。

本目录的 SQL 是给 **SSO 管理方**（`jeecg_boot` 库）做本地联调用的，接入方不执行。

## 2. 选哪个脚本

| 模式 | 脚本 | 内容 | 数据库 auth_mode 存储值 |
| --- | --- | --- | --- |
| 普通单点登录 `SSO_ONLY` | [普通单点登录_SSO_ONLY.sql](普通单点登录_SSO_ONLY.sql) | 只登记客户端（`sys_sso`） | `1`（对应字典 `sso_auth_mode`：普通单点登录） |
| 页面受控 `PAGE_CONTROLLED` | [页面受控_PAGE_CONTROLLED.sql](页面受控_PAGE_CONTROLLED.sql) | 权限项、授权给 admin、登记客户端、登记三个页面 | `0`（对应字典 `sso_auth_mode`：页面受控） |

- **模式存储与映射**：SSO 服务端数据库 `sys_sso.auth_mode` 使用数值存储（`0` 受控，`1` 单点），并在管理端通过数据字典 `sso_auth_mode` 翻译为中文；接入方（B 系统）配置 `.env` 保持可读的枚举名（`PAGE_CONTROLLED` 或 `SSO_ONLY`），客户端代码与外部配置无需任何变动。
- **切换模式**：两个脚本登记的是同一个客户端 `sso_demo_001`，切换模式前先 `DELETE FROM sys_sso WHERE id = 'sso_demo_001';`，并同步修改 `.env` 的 `SSO_AUTH_MODE`。
- **角色权限**：页面受控脚本把权限授予 `admin` 角色，测试时**登录账号必须拥有 admin 角色**，否则页面会被拒绝。

## 3. 密钥：明文与密文

| | 谁持有 | 内容 |
| --- | --- | --- |
| 明文 | 接入方 `.env` 的 `SSO_CLIENT_SECRET` | 由 SSO 管理端（common 模块）生成，创建或重置时只展示一次 |
| 密文 | SSO 数据库 `sys_sso.client_secret_hash` | 明文的 SHA-256（64 位小写），脚本里的 `afc6bf…` 就是它 |

脚本里的密文必须与 `.env` 中的明文配套，否则换取 Token 会失败。更换成自己的密钥：在管理端生成或重置，不要自己编造。完整的生成、交付、重置流程见[配置说明](../docs/配置说明.md#3-客户端密钥sso_client_secret详解与全生命周期)。
