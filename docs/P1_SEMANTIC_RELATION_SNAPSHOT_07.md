# P1-SEMANTIC-RELATION-SNAPSHOT-07：独立模型候选版本快照与权限单调失效

日期：2026-10-17；分支：feat/finebi-analysis-engine-p0。

## 交付范围

新增仅管理员可调用的 POST /api/model/semantic-relations/{relationId}/release-snapshots，严格接受 revision、readerId、acknowledgeNotPublished=true。仅对当前关系已批准、源数据指纹一致、读者 prepublish access-review 仍 READY_FOR_REVIEW 的组合封存不可变候选快照。单关系+读者加行锁生成连续 release_no，保存候选关系修订、双方源元数据 SHA-256 指纹、读者权限指纹、受控字段映射、候选快照 SHA-256 完整性校验码。候选永不存储 SQL 文本，不访问业务 JDBC。

GET /api/model/semantic-relations/{relationId}/release-snapshots?readerId=... 列出至多 50 个候选及动态状态：SEALED_ONLY、STALE、BLOCKED。读取时重查关系批准版本、SQL/字段指纹、存储快照校验码、合法受控 JOIN 编译及读者当前列 deny/行策略、账号与 auth_version。任何异常安全返回失效；不返回绑定 JSON、SQL 或业务数据。

insight_semantic_release_snapshot 为新增幂等元数据表，UNIQUE(relation_id,reader_id,release_no)，只 insert 不覆盖快照。不得用于报表真实发布或读者执行。

为避免一条 ACL 被添加后又删除导致旧候选误恢复为有效，GovernanceApi 修改读者角色、列拒绝或行规则时在同一数据库事务中 auth_version+1；身份会话随之失效，读者需重新登录。账号激活、密码变更此前已递增 auth_version。本批仅监控经过这些管理接口的权限变更；绕过 API 的直接数据库写入不提供单调变更检测保证。

## CI

Java H2：封存必须经过审查、只封存不可发布、连续版本、权限阻断、policy/auth 变更、源 SQL/关系修订漂移、存储快照篡改、schema 幂等、治理接口的 auth_version 单调递增；既有浏览器及其他相关专项 CI。

## 明确未实现

这不是可执行模型发布：没有接入 insight_dashboard_version、ReaderApi、SQL JOIN 执行、双侧 row policy 下推、最终关联行数或真实 StarRocks 验收。SEALED_ONLY 不是 PUBLISHED。生产/18110/18130 均未部署；新增 DDL 必须先通过元数据库一致性备份与恢复门禁。

下一批：受控地设计报表组件显式引用候选模型版本及完整双侧行列权限下推与发布检查，而不是直接启用 SQL 预览。

## 管理员端 UI

审查对话框新增“封存候选版本”按钮：仅在当前读者审查报告 READY_FOR_REVIEW 时可点，二次弹窗明确不发布、不授予读者权限；管理员可查看各候选版本的 SEALED_ONLY、STALE、BLOCKED 动态状态。切换读者或 JOIN 模式会清除本地可封存状态。Chromium smoke 覆盖确认审查、封存和列表显示。
