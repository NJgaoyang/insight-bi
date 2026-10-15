# P3-01 元数据加密备份与隔离恢复

## 交付范围

系统管理 → 备份恢复可由现有管理员下载 `.ibak` **加密元数据备份**、上传一个备份文件进行**非破坏性校验**。后端接口为：

- `GET /api/admin/metadata-backup`：输出随机 IV + AES-256-GCM 密文，禁止响应缓存；会话必须是 `admin`。
- `POST /api/admin/metadata-backup/verify`（`application/octet-stream`）：验证解密、完整性标签、20 张白名单表、字段结构与备份文件大小；会话必须是 `admin`，且写请求需要 CSRF。
- **没有 HTTP 恢复接口**。只能停机后使用离线命令将备份恢复到隔离的**全新空数据库**，不能覆盖现有数据库。

备份涵盖 20 张 `insight_*` 元数据表：数据连接（凭据在源库中已经加密）、数据集与字段、报表/发布/历史版本、工作区目录、用户/部门、行列级权限、授权、审批与审计、数据加工元数据。

**不包括** StarRocks/MySQL 业务数据源中的原始业务表、附件、服务配置文件、TLS 证书或外部对象存储。

备份使用 `BI_CREDENTIAL_KEY` 派生的独立 AES-GCM 密钥。**备份文件、原始密钥必须分开保存**；密钥丢失无法恢复且不能通过修改用户密码代替原密钥。恢复时须保留旧密钥，否则原有数据连接的加密凭据也无法解密。禁止将 `.ibak`、密钥或明文恢复数据上传公开代码仓库。

每次加密使用全新 12 字节 IV 和认证标签。备份是逻辑快照，导出时使用 `REPEATABLE_READ` 事务，避免跨表元数据版本混杂。单次备份明文限制 32MB，各表行数限制 10 万条；超限会**失败**，不会输出看似成功但不完整的备份。

## 首次备份 / 日常例行备份

1. 使用管理员账号进入「系统管理 → 备份恢复」，点击“下载加密备份（.ibak）”。
2. 下载后的文件保存在受控离线存储中；原始 `BI_CREDENTIAL_KEY` 在另一个受控位置保管。
3. 在“校验已有备份”选择已下载文件，确认显示 `valid=true`、20 张表和合理的记录总数。
4. 恢复能力验证必须在隔离空库上执行；**仅校验文件不等于成功恢复**。

生产环境还须单独规划按数据库厂商推荐的物理/增量备份；本功能不能代替 H2/MySQL 的灾难恢复与停机策略。

## 恢复操作（仅隔离空库）

**必须先停止旧 Insight BI 进程**；不能在仍运行的旧应用或现有元数据数据库上执行恢复。创建一个与生产分离、全新空白的 H2 文件或 MySQL 数据库，并确认它的地址。

示例（H2 **新**恢复文件路径；请勿照搬到已有运行库）：

```bash
cd backend

export BI_CREDENTIAL_KEY='<原部署环境的 base64 32-byte key>'
export SPRING_DATASOURCE_URL='jdbc:h2:file:/safe/restore-target/metadata;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE'
export BI_RESTORE_EXPECTED_JDBC_URL="$SPRING_DATASOURCE_URL"
export SPRING_DATASOURCE_USERNAME='sa'
export SPRING_DATASOURCE_PASSWORD=''

mvn -q -DskipTests compile exec:java \
  -Dexec.mainClass=com.insightbi.MetadataRestoreCli \
  -Dexec.args='/safe/offline/backups/insight-bi-metadata.ibak --confirm-empty-only'
```

如使用 MySQL，`SPRING_DATASOURCE_URL` 指向**新创建的空库**，设置其独立的用户名/密码，并使用原密钥。 `BI_RESTORE_EXPECTED_JDBC_URL` 必须与连接 URL 完全一致，另加命令行 `--confirm-empty-only`，避免误选连接目标。

程序执行顺序：

1. 读取并校验离线备份文件和原始密钥。
2. 初始化 **目标** 新库兼容的元数据结构。
3. 验证 AES-GCM、字段及 20 张表清单。
4. 检查 **所有 20 张元数据表均为空**；有任意数据时立即拒绝。
5. 在数据库事务内插入元数据快照，失败应回滚。
6. 显示恢复摘要（表数与行数，不显示敏感字段）。

之后先在**隔离环境**启动应用：检查目录/用户/报表数量、数据连接（不暴露密码）、发布历史及授权；比对源备份的摘要，再决定是否由运维切换服务。对新环境的用户密码哈希和加密连接字段均会保留原值。

不支持“合并两个环境”或“向已有库覆盖恢复”；不要使用手工 SQL 关闭约束后绕过空库前提。若恢复出错，删除隔离目标库并重新创建全新空库再排查；**不要在源库上直接重试覆盖**。

## 定向验收和待办

`MetadataBackupRecoveryTest` 使用两套独立 H2 内存库：

- 全表清单的密文输出、完整性与伪造损坏拒绝、错误密钥拒绝；
- 已发布报表/审批历史/用户哈希/数据连接加密凭据等元数据往返；
- 只允许空库恢复，拒绝向非空库重复恢复；
- 管理员 API 返回二进制密文并禁止缓存。

运行：`cd backend && mvn -q -Dtest=MetadataBackupRecoveryTest test`。

**尚未完成**：生产环境 MySQL 迁移/恢复演练、真实 StarRocks 业务数据备份、定时备份、异地存储和密钥轮换管理。不得将此功能视为已完成生产级容灾体系。
