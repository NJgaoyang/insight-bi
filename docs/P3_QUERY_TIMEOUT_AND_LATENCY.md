# P3-07 V2 查询超时、慢查询监控与资源释放

日期：2026-10-15。目标：在原有查询边界基础上补足错误语义和可观测性，不增加复杂的重试、自愈或者与现有 StarRocks 服务相关的破坏性故障演练。

## 沿用的既有安全边界（未重复开发）

- V2 业务源 JDBC `PreparedStatement.setQueryTimeout(15)`，15 秒是 **JDBC 驱动发起的取消请求**，不是保证任何情况下 15 秒内可强制结束的全链路上界；
- JDBC connect timeout 5 秒、socket timeout 18 秒；两者与 SQL statement 超时不是同一回事；
- V2 同实例最多 4 并发、最多等待 250 毫秒，超过限额时返回 HTTP 429；
- 每次查询都在 try-with-resources 中关闭 JDBC `ResultSet`、`PreparedStatement` 和外部 `Connection`，并在 try-with-resources 中释放并发许可；没有任何 SQL 自动重试；
- 数据源仅在**连续连接类异常**触发独立的 30 秒熔断机制，SQL 语法、字段校验和正常的 JDBC 查询超时不应隔离整份数据源。

## 本轮实际修改

1. `AnalysisExecutionMetrics` 是 Spring 单例、有界、进程内的 V2 查询指标聚合器。保留**成功数、失败数、查询超时数、慢查询数（≥3秒）及累计执行毫秒数**，不包含 SQL、来源主机、数据集/用户 ID、绑定值、异常文本或密码。重启归零。
2. `AnalysisApi.query` 在真正调用查询限流器到返回/失败的生命周期中记录执行结果，原有 JDBC finally 关闭逻辑保留；失败次数不等同于故障连接数。
3. `OperationsApi` 的原有管理员专用 `GET /api/admin/operations/status` 新增 `queryExecution` 字段。前端「系统管理 → 运行监控」增加 V2 查询耗时与超时表格；兼容没有该字段的旧接口返回。
4. 遇到明确的 `SQLTimeoutException`，V2 分析查询和普通读者已发布 V2 报表统一返回脱敏 **HTTP 504**，正文为 `分析查询超时，请缩小数据范围后重试`；不回显数据库、SQL、账号或凭据。原有超并发 HTTP 429 与断连熔断 HTTP 503 均不改变。
5. 对 JDBC `SQLTimeoutException` 以及标准 HYT00/HYT01 查询截止状态，独立指标进行超时计数；StarRocks/MySQL 驱动差异导致的非标准超时可能由驱动作为连接错误返回，仍应结合客户端超时/服务端查询日志分析。熔断器新增明确的超时短路排除逻辑，即便超时对象携带连接类 SQLState 也不因单次 SQL deadline 将健康的数据源熔断。

## 定向验证

- `AnalysisQueryTimeoutTest`：使用 Mockito 注入 `PreparedStatement.executeQuery` 抛出 JDBC SQLTimeoutException，不需要真实等待 15 秒，也不影响现有 MySQL/StarRocks。
- 检查调用 `setQueryTimeout(15)`、读取行数上限、超时后关闭语句和连接、释放限流器许可，并可在相同数据源执行下一次真实 H2 查询；检查失败/超时计数与 504 脱敏正文。
- `OperationsMonitoringTest`：管理员指标中可见超时/失败计数，但看不到 JDBC 地址及密码；全局报表 API 统一 504。
- `SourceRecoveryGuardTest`：即使 SQLTimeoutException 携带 `08xxx` SQLState，仍不能触发数据源级熔断。
- Vue/TypeScript 定向构建验证前端运行监控面板；不执行全量回归。

## 风险与待做

- **不能承诺任意慢 SQL 会被精确强制杀死**。JDBC 超时的具体执行依赖 StarRocks/MySQL Connector/J 的支持，以及网络和数据库负载。长时间阻塞的 socketTimeout、连接池和服务端查询限制都需针对真实部署检查。
- 这里统计的 V2 查询，不覆盖原有 V1 API 或其他手工 SQL 预览路径。没有记录 SQL，无法直接从指标面板找到具体哪条查询慢；应结合受控的服务器数据库查询日志定位。
- 未执行在线 StarRocks FE/BE 停机、故障切换、网络分区与生产性能压测。本批只在本地/H2/故障注入检查错误语义和资源释放。
- 后续 P3 可安排**隔离源库慢 SQL 服务端取消验证和基线容量测量**，但不应对用户当前正在使用的 StarRocks 实例执行人为中断或长时阻塞。

本批属于 P3 单一可靠性切片，不代表所有上线前可靠性工作已完成。
