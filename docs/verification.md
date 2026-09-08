# 验证记录

本地验证：

- `npm ci`、`npm run build` 成功（包含 vue-tsc 类型检查）。
- Spring Boot 2.7.18，`mvn test package` 成功：13 tests，0 failures，0 errors，0 skipped。
- 本地 Maven 使用 JDK 17，项目 source/target 为 1.8；JDK 8 运行验证由 `.github/workflows/build.yml` 完成。
- 测试覆盖 RFC 7636 向量、HMAC 内容绑定、SID 撤销与迟到回调、身份/状态/登出声明、CSRF、真实 H2 用户落库与一次性回调。
- Web 回调测试使用模拟 SsoClient，未连接真实 SSO。SQL 的 SSO 登记模板未在真实数据库执行。

尚需部署环境验证：Portal 前端跳转、SSO/Redis/MySQL 实际联调、两客户端被动登出、用户禁用、真实 JDK 8 CI 结果。此记录不等于生产验收。
