# 数据库说明

SSO V2 客户端和页面资源必须通过 `DCS4/yth` 管理端创建，以便：

- 后端生成 client_id 和一次性 client_secret；
- 数据库只保存 Secret 的 SHA-256 摘要；
- 统一校验系统地址、固定回调路径、页面路径和权限编码；
- 密钥轮换立即生效。

因此本示例不再提供直接写 SSO 数据库的注册 SQL，也不保留旧 `sso_client`、`sso_client_redirect_uri` 模板。

本 Demo 将用户信息和页面 Token 放入服务端 HttpSession，不需要自己的示例数据库。真实业务系统可把用户映射和 Token 元数据放入现有数据库或共享 Session 存储，但不得保存或返回 SSO 密码字段。
