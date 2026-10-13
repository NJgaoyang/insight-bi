# P0 发布门禁：H2 一致性备份 / StarRocks 真数对账 / 回滚

> 2026-10-13 · 本文是发布操作约束，不是生产发布完成证明。当前 `feat/finebi-analysis-engine-p0` 仍是开发分支。

## 现场结论（尚未满足发布条件）

- 运行中的 `insight-bi.service` 使用 **H2 文件型元数据库**，而不是历史文档期望的 MySQL `datasphere`；请现场再次核对实际 JDBC 配置。
- `/opt/insight-bi/runtime/local.env` 缺失；在恢复服务器专属配置前**禁止停止或重启**现有生产服务。
- 未取得生产 H2 的事务一致性备份；当前已有的 H2 文件复制不视为已验证的可恢复备份。
- 尚未使用真实的非生产 StarRocks 只读账号完成 JUnit 对账。Maven exit 0 但 Surefire `skipped=1` **不算通过**。
- P0-05 的真实数据库、旧版本上线兼容和生产回滚均尚未验收，禁止把发布门禁勾选为完成。

## 1. 从生产运行环境获取真正一致的 H2 备份

**先恢复 `runtime/local.env` 与旧服务 JAR 的可靠启动条件**，并核对元数据库实际路径、H2 版本与账号。不要直接复制正在运行中的 `metadata.mv.db` 当权威备份。

合格备份应由数据库所有者通过 H2 的 **`BACKUP TO`**（从同一运行 JVM 的 JDBC 连接执行），或经授权的完整停机维护流程生成。仅生产服务的管理员在批准的变更窗口处理；本分支不在当前运行服务中暴露临时备份 HTTP API。

将经确认生成的一致性 ZIP 放入服务器私有的 `/opt/insight-bi/runtime/p0-metadata-consistent.zip`；确保目录权限仅管理员可读写，勿提交至 GitHub。

## 2. 在隔离目录验证备份可恢复

确认安装与元数据库兼容的 H2 JDBC jar 路径。以下脚本不会打开生产 H2 数据库，而是在受限临时目录解压备份，通过只读 JDBC 打开恢复副本，并校验数据集、分析主题、历史版本、字段及公式等元数据表：

```bash
cd /opt/insight-bi
# 请填本机真实的 H2 JAR 路径（不要放入代码或提交个人目录）
P0_H2_JAR=/path/to/h2.jar bash scripts/p0_verify_h2_restore.sh
```

如数据库设置了 H2 用户名/密码，使用 `P0_H2_RESTORE_USER` 与 `P0_H2_RESTORE_PASSWORD` **通过安全的环境注入**，不要写在 shell 历史、命令行参数或 Git 中。验证成功后生成权限为 `600` 的 `runtime/p0-h2-restore.sha256`；备份发生变更后必须重新恢复并验证。

`MetadataRecoveryRehearsalTest` 和 `test_p0_release_safety.sh` 的合成测试通过，不代表此步骤已对真实生产数据执行。

## 3. 使用专用非生产只读账号完成 StarRocks 真数对账

仅对已确认的非生产 StarRocks 使用**非管理员且数据库层面无写入/DDL 权限**的专用账号；JDBC `setReadOnly` 只是辅助保护，不替代数据库的权限设计。测试脚本使用 SELECT/UNION ALL 构造固定数据，不写业务表。

```bash
cd /opt/insight-bi
# 下面变量通过批准的机密管理方式注入，不保存到 Git
# P0_STARROCKS_E2E=true
# P0_STARROCKS_NONPROD_CONFIRMED=true
# P0_STARROCKS_JDBC_URL=jdbc:mysql://<nonproduction-host>:9030/<test_database>
# P0_STARROCKS_USER=<nonproduction_readonly_user>
# P0_STARROCKS_PASSWORD=<secret>
bash scripts/p0_starrocks_readonly_e2e.sh
```

两个启用标记缺失、连接缺失、账号为 `root/admin` 均直接拒绝。JUnit 返回 **tests ≥ 1、skipped = failures = errors = 0** 后，脚本才将实际 Surefire XML 以权限 `600` 归档到私有 `runtime/p0-starrocks-surefire.xml`。

## 4. 发布前只读核验及部署

```bash
cd /opt/insight-bi
bash scripts/p0_release_preflight.sh
# 仅在完整 G0/G1~G4 和 P0-05 人工验收后，且经明确批准的变更窗口：
# P0_APPROVE_DEPLOY=true bash scripts/deploy.sh
```

发布检查要求生产配置、旧 JAR、H2 ZIP 与恢复 SHA-256 一致、有效 StarRocks XML、原服务 `18110` 健康。任意缺失均退出并拒绝发布。新版 `deploy.sh` 在**停止服务前**先完成 Vue 构建与隔离 Maven 打包，再备份旧 JAR、替换并启动，健康检查失败自动尝试恢复旧 JAR。

**回滚边界：** JAR 回滚不能自动回退元数据库模式或业务数据。本阶段仅允许有可恢复备份且兼容旧版的增量迁移；实际线上回滚演练、故障演练均未执行。不要通过修改检查文件、伪造报告或关闭检查来绕过门禁。
