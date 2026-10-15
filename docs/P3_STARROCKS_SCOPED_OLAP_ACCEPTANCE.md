# P3-06 真实 StarRocks OLAP 数据集与服务器端只读权限验收

验收日期：2026-10-15。操作设备：用户授权开发机 `VM-0-13-tencentos`，工程目录 `/opt/insight-bi`。目标 FE：`81.69.15.136:9030`。

## 实际结果

**PASS：StarRocks OLAP 表 → 专用只读账号 → Insight BI V2 AnalysisApi.query → 参数化聚合**，不只是在虚拟 SELECT 常量源上运行。

- 实际 StarRocks 中创建**随机命名的临时测试数据库**、仅包含 4 条合成数据的 OLAP 表；没有对已有业务数据库或其表执行任何 DDL/DML。
- 随机生成专用测试用户及强密码，仅授予该测试表的 `SELECT` 权限，不保留管理员访问权。
- 不依赖 JDBC `setReadOnly(true)` 提示：实际使用该测试账号尝试 `INSERT`、`CREATE TABLE`，两者均由 StarRocks 服务器拒绝（JDBC SQLException）。
- 使用该测试账号，通过系统真实 `DatabaseStore.open`、`FieldCatalog`、`AnalysisApi.query`、`AnalysisQueryPlanner` 对 StarRocks OLAP 表执行 V2 参数化聚合，验证单城市与多城市结果。
- 执行脚本始终注册 EXIT 清理：删除所创建的临时用户和数据库，并销毁临时只读账号密码文件。实测三个脚本执行中产生的测试数据库均不再存在，查询残留数量 **0**。临时管理员凭据文件已单独安全删除。
- Maven Surefire：`StarRocksScopedAccessTest`，**Tests run: 1, Failures: 0, Errors: 0, Skipped: 0**；最后一次执行成功退出码 0。
- 验收测试仅对新增测试模块进行编译执行，没有全量回归或生产版本部署。

## 验收数据（完全合成）

| 城市 | 原始测试记录 | V2 SUM |
| --- | --- | ---: |
| 北京 | 100、50 | 150 |
| 上海 | 200 | 200 |
| 广州 | 300 | 300 |
| 北京 + 上海 | 前两组 | 350 |

已验证 `IN (?,?)` 等 JDBC 参数化过滤，来源为实际 StarRocks OLAP 表而非 `UNION ALL SELECT` 虚拟数据。金额类型由 StarRocks JDBC 返回为 `DECIMAL(12,2)`，测试按精确数值处理其 JDBC scale（不能把 `150` 与 `150.00` 视为不同金额）。

## 操作/复验

新增：

- `backend/src/test/java/com/insightbi/StarRocksScopedAccessTest.java`：仅对**随机专用数据库和只读用户**执行的 opt-in 真实 V2 测试。
- `scripts/p3-starrocks-isolated-fixture.sh`：负责生成随机测试数据库、模拟数据、只读账号和授权，调用上述 JUnit，并在脚本退出时清理。

只在明确授权、可以创建并删除专用临时测试资源的开发/测试 StarRocks 实例运行。先在本机建立权限为 `0600` 的 MySQL defaults 文件（密码通过安全输入，不写入 Bash 历史记录）：

```ini
[client]
user=root
password="在本机安全输入，不得提交到 GitHub"
```

```bash
cd /opt/insight-bi
export BI_STARROCKS_ADMIN_DEFAULTS_FILE=/path/to/root-only-starrocks.cnf
bash scripts/p3-starrocks-isolated-fixture.sh
```

脚本要求 options 文件权限严格是 `0600`；所有读取和测试账户密码仅保留在本地临时文件。测试模块默认因缺少 `BI_ACCEPTANCE_SCOPED_STARROCKS=yes` 而跳过，所以没有真实数据库时不能将空环境下的 CI 成功视为 StarRocks 实测通过。

## 不属于本次验收的内容

- 生产业务表的语义正确性、SQL 数量/性能边界、实际外部 BI 用户的行列 ACL；
- FE/BE 主动关停、集群主从切换、恶意访问安全测试；
- 长期运行的独立 BI 只读服务账号（本次临时账号已销毁；正式服务账号需要单独办理权限和密钥管理）；
- 最新系统整体部署、生产数据库迁移、全部业务回归和多租户隔离。

本次结论仅适用于**指定开发环境 StarRocks 的真实 OLAP V2 聚合及最小权限读者验收**，不得将其扩大成生产正式上线验收。
