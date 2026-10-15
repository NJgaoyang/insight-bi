# P3-10 真实 HTTP 读者会话、发布权限与 CSRF 专项验收

## 本次目标

上一阶段 P3-09 的 8 读者/60k 行查询已在 GitHub Actions 的一次性 MySQL 8.4 实例中成功运行，但其读者调用是 JVM 内的 Controller 直接调用，没有经过实际 Tomcat、JSESSIONID Cookie 和 Spring MVC 拦截器。本批补上这部分**真实 HTTP 入口**验证，不修改生产数据。

## 验收范围

- 在测试 JVM 中启动真实 Spring Boot Tomcat（端口随机，只绑定 `127.0.0.1`）。Insight BI 自身元数据存储于隔离 H2 内存库；业务源只有 GitHub Runner 的一次性 `mysql:8.4` 服务 `127.0.0.1:3306/insight_ci_acceptance`。
- 构建 9000 条合成城市收入数据，两城市各 4500 条、每条 10，预计各自汇总收入 45000；测试结束后通过 `finally` 删除测试表并销毁 GitHub Runner 的数据库容器。
- 使用 8 个不同 HTTP `HttpClient` 与独立 CookieManager 分别 POST `/api/reader/login` 建立真实 Cookie 会话，真实 GET `/api/reader/catalog` 和 `/api/reader/dashboards/{id}`，之后访问已发布 widget 查询 API。
- 权限断言：无会话查询得到 HTTP 401；普通读者即便已登录也不能访问 `/api/connections` 等管理员接口（401）；写请求缺失/伪造 CSRF 返回 403；提交自造 dataset/filters 拒绝；8 个账号分别仅有北京或上海行级过滤，禁止交叉城市泄漏。
- 8 个独立会话同时进行 2 次 HTTP widget 查询，总计 16 次；每次成功的查询必须返回所属城市且汇总为 45000；并发额度外的查询允许合法 HTTP 429、绝不静默重试。压力结束检查 4 并发槽释放为 0。
- 已登录会话发生报表授权撤销时立即返回 404；另一读者账号被管理员禁用时旧 Cookie 立即返回 401。生成 JSON 汇总到 `backend/target/p3-http-reader-acceptance.json` 并上传 CI artifact。
- 启动级别检出此前被 Controller H2 模拟测试掩盖的事务代理故障：`AnalysisPipelineApi` 原先标记为 `final`，但包含 `@Transactional` 方法，Spring CGLIB 无法生成代理；本批修正为非 final class，**不改数据加工 API 接口语义**。

## 运行方式

专项 GitHub 工作流：`.github/workflows/p3-http-reader-acceptance.yml`，仅测试文件、工作流或事务 API 变动时触发：

```bash
cd backend
# Only with disposable MySQL service set up explicitly:
mvn -B -q -Dtest=HttpReaderSessionAcceptanceTest test
```

没有 `BI_ACCEPTANCE_DISPOSABLE_MYSQL=yes` 时测试主体明确跳过；不能把空环境下的编译成功当作真实 HTTP + MySQL 验收成功。该工作流不运行全量回归、不部署线上服务、不访问用户现有的 StarRocks 或生产 MySQL。

## 已知边界

- 这是单 JVM 真实 HTTP/Tomcat/会话/CSRF/MySQL 测试，不是 Chrome 浏览器中的完整页面访问；不能验证前端渲染、Nginx 配置或 HTTPS SameSite Cookie 行为。
- 16 次查询和 9000 条模拟数据只证明有限并发和安全边界，不是生产 P95 SLA；仍需生产前的浏览器/代理专项验收、跨实例共享会话及混合故障恢复演练。
- 不把当前 `final` 类修复之外的旧模块进行重复开发，不做大范围架构重构。
