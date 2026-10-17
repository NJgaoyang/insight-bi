# P1-SEMANTIC-RELATION-DUAL-RLS-PLAN-09：双侧行权限 SQL 编译预览

日期：2026-10-17；开发分支：feat/finebi-analysis-engine-p0。

## 接口和边界

管理员（Session + CSRF）调用 POST /api/model/semantic-relations/{relationId}/rls-plan-preview，请求固定五项：revision、readerId、joinType（LEFT/INNER）、leftFieldIds、rightFieldIds。只接受当前已批准 revision 和源/字段指纹一致的同连接关系。返回 SELECT-only、LIMIT 200 的 **COMPILE_ONLY_DUAL_SOURCE_RLS** SQL 预览，左右各自 RLS 过滤数量、总参数数量、读者 auth_version 和安全警告；永不返回 allowlist 值，不借用外部 JDBC，不执行 SELECT/JOIN，不修改 ReaderApi、AnalysisApi、报表发布或候选审查策略。

对已启用 VIEWER/EDITOR，服务端从 insight_reader_row_policy 与 insight_reader_row_policy_value 读取单值/最多 20 个值。行规则字段必须是当前源数据集的非隐藏物理 STRING 字段；任何列 deny 均拒绝。本接口独立于此前保守预发布 access-review（其仍会对任一行规则返回 BLOCKED，候选封存/草稿绑定/正式发布限制不放宽）。用户端无法提交查询参数或授权值。

## SQL 安全

每侧结构为 SELECT * FROM (validated_source_sql) AS bi_rls_<side> WHERE physical_policy_column = ? 或 IN (?,?,...)，然后分别嵌入 bi_left / bi_right JOIN。**右侧 RLS 必须在右侧 JOIN 输入内**，不能落在 LEFT JOIN 结果的 WHERE 上，以保留没有右表命中的左行。输出、关联键和权限列均由字段目录解析、SQL 标识符引用；值只用 JDBC 位置占位符。参数顺序定义为左策略后右策略，但预览 API 不返回可执行参数数组；运行时未开放。只读 SELECT AST、关联键类型、受控输出字段及 SQL 长度均复用守卫或再次检查。

## 测试

H2 检查：左/右单值和多值参数顺序，预处理 SQL 可运行性和 LEFT JOIN 空右行语义，策略值不会进入 SQL 文本，禁用读者/列 deny/隐藏或非文本行策略拒绝，源 SQL 改动和草稿修订漂移拒绝，畸形策略值或多值集合拒绝。前端 Chromium 验证管理员预览入口和非可执行提示。

## 不支持

实际读者双侧 RLS 执行、源 SQL 潜在间接字段血缘、聚合及 JOIN 扩行风险总量证明、含 RLS 的候选版本正式发布、真实 StarRocks 测试均未实现；不得将 SQL 预览直接放入 ReaderApi。无生产部署，无新 DDL。

## 管理员界面

已确认关系的“预发布访问审查”对话框新增“预览双侧行权限 SQL”。选择读者及 LEFT/INNER 关联后调用新接口，展示两侧 `?` 绑定数量、身份版本、SQL 占位符文本及不可执行警告。切换读者或关联模式时清除上一份预览。Chromium 专项覆盖请求固定字段、UI SQL/警告以及既有审查/候选保存回归。
