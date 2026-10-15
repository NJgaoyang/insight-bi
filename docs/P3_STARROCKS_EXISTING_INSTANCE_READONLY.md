# P3-05 指定 StarRocks FE 只读 V2 验收（2026-10-15）

## 指定连接

本次用户指定的 StarRocks FE 地址为 `81.69.15.136:9030`，协议为 MySQL Wire Protocol。**连接密码只来自运行时环境或无回显终端输入，绝不进入代码、GitHub 工作流、测试日志或本文件**。

在当前执行环境实际尝试 TCP 连接 `81.69.15.136:9030` 得到 `Connection refused`；同一运行容器对外网其他端口也出现连接拒绝或 DNS 无法解析，说明当前环境自身可能限制外连，不能据此断言远端 StarRocks 停机。**真实 FE SELECT 验收尚未完成**。

## 本轮提供的安全验收

- `StarRocksExistingInstanceReadOnlyTest` 内置一个由四条 **SELECT 纯字面量**组合的虚拟数据集：北京 100+50、上海 200、广州 300；不依赖也不读取远端的任何现有业务表。
- 使用实际 `FieldCatalog`、`AnalysisQueryPlanner`、`AnalysisApi.query` 和 `DatabaseStore.open` 的 V2 查询链路，执行北京单城市及北京+上海/上海+广州多值聚合，验证结果分别为 150、350、500。查询用 JDBC 参数化 `IN` 条件，不拼接用户输入的 SQL。
- 只使用目标 StarRocks 的 `information_schema` 作为连接默认数据库；测试中的 `insight_*` 元数据仅建立在**本机临时 H2 内存库**，不会写到远端 StarRocks。
- 实测路径只执行 `SELECT 1` 和虚拟数据集聚合查询；**不会**在远端执行 `CREATE DATABASE`、`DROP`、`INSERT`、`UPDATE`、`DELETE`、`GRANT` 或修改用户权限。
- 在没有显式环境变量 `BI_ACCEPTANCE_EXISTING_STARROCKS=yes` 时，真实 FE 测试会明确被 JUnit 跳过；离线 SQL 编译/占位符测试仍然运行。

### 在有网络连通性的授权 Linux 机器上执行

脚本在密码未设置时使用无回显终端提示，并先探测 TCP 端口：

```bash
cd insight-bi
export BI_ACCEPTANCE_STARROCKS_USERNAME='root'
bash scripts/p3-starrocks-existing-readonly.sh
```

执行时需要在提示中输入本次指定的 StarRocks 密码；**不要在命令历史或 GitHub Actions YAML 中写明文密码**。如果将来改为只读账号，可保持同一脚本，只改 `BI_ACCEPTANCE_STARROCKS_USERNAME`。

连通性故障诊断（在 FE 主机或相同网络内执行，不输出密码）：

```bash
ss -lntp | grep ':9030'
mysql -h 127.0.0.1 -P 9030 -u root -p -e 'SELECT 1'
```

若本机 `127.0.0.1:9030` 可访问但公网不可访问，检查 StarRocks FE 监听地址、安全组、主机防火墙和客户端出站策略。无需关闭防火墙或开放到整个公网；优先在同一私网或经批准的安全隧道运行验收。

## 重要边界

当前使用的是管理权限很高的 `root` 账号，因此这次即使 SELECT 成功，也**不能**证明服务端最小权限/禁止写入机制。生产 BI 应单独创建并授予 SELECT 的账号，并在受控环境验证 DML/DDL 被拒绝；不要用 root 作为正式业务读取账号。

此批没有停机、重启 FE/BE，没有做真实 FE 切换或业务表破坏试验。真正的断线恢复验收需要经过批准的独立窗口或专用测试集群，不应随意中断该用户提供的现有 StarRocks 服务。

### 本次状态

| 检查 | 状态 |
| --- | --- |
| V2 虚拟城市数据集的只读 SQL 规划、IN 参数化 | CI 通过 |
| 将地址、密码保存到仓库 | 未执行（禁止） |
| 当前运行环境 TCP 直连 `81.69.15.136:9030` | Connection refused |
| 目标 FE V2 聚合 JDBC 执行 | 未执行（网络不可达） |
| 服务端账号最小权限、FE 故障切换及业务库数据核验 | 未执行 |

不能将离线 SQL 编译成功或 MySQL 8.4 CI 通过报告为真实 StarRocks 验收通过。
