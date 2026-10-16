# P1-CROSS-DATASET-MAPPING-01 · 同连接跨数据集显式维度映射（2026-10-16）

## 本批实现

在已交付的“统一视图筛选组件”之上，允许同一视图内的来源数据集 A 稳定字段 ID，**显式映射**到目标 Widget 的另一个数据集 B 稳定字段 ID。不使用同名猜测、SQL 拼接或跨库自由 JOIN。

- 视图保存 `filter.mappings=[{widgetId,fieldId}]`，只存目标 Widget ID 与目标稳定字段 ID，不保存输入值。映射只能在目标组件的 V2 维度槽里选取，需数据集元数据 `connection_id` 相同、双方物理字段 `logicalType` 完全一致；没有映射的异数据集组件不联动。每个目标最多一个映射。
- 编辑器在选择视图筛选字段后，按需读取已授权数据集目录；显示符合条件的目标维度。保存前再次检查所有映射均是同连接、合法稳定字段、严格同类型。复制视图时深复制映射数组，防止另一视图被修改。值不进入撤销历史或发布快照，仍只在“应用”后触发查询。
- Admin 编辑与发布查看只向实际绑定的目标组件发送一个 `filters.pre.eq`；目标数据集、查询维度/指标由对应 Widget 自己的原配置指定，不因映射改变。
- 读者端仍严格只接收 `{viewId,filterValue}`，由服务器核验：已发布视图存在、目标 Widget 为当前视图成员、映射条目唯一且存在、映射目标稳定字段在目标 Widget 的已发布维度槽、源稳定字段也属于同一视图的已发布源 Widget 维度、双方数据集 `connection_id` 相同且服务端 FieldCatalog 物理字段类型相同；再由原有 `ReaderColumnGuard` 检查已发布内容的列级访问权限，把受控筛选与行级强制过滤合并送往 `AnalysisQueryPlanner` JDBC 参数化执行。
- 改变或伪造 `datasetId`、`fieldId`、SQL、额外过滤列表和跨数据集连接均拒绝；同名但无显式映射拒绝，不兼容类型或源/目标字段缺失 fail closed。
- 本批只支持单个“视图筛选器”选择一个字段、按单个目标 Widget 建立等值字段映射。**不提供**跨连接联动、自动类型转换、不同授权范围的共享结果、任意字段关联和联合查询；不新增数据库元表和 HTTP API。

## 定向验收

- Node：`node --experimental-strip-types --test scripts/analysis_view_filter.test.mjs scripts/analysis_workspace_layout.test.mjs`，检查不同 stable fieldId、同连接、类型完全一致、不允许隐式同名映射、跨视图隔离和数据不变性。
- Java/H2：`cd backend && mvn -B -q -Dtest=ReaderCrossDatasetFilterTest,ReaderViewFilterTest,ReaderApiTest test`，验证来源/目标物理类型、跨连接拒绝、恶意参数作为 JDBC 参数、同一目标重复映射拒绝、读者强制行级 ACL 与旧读者端接口兼容。
- Vue：`cd frontend && npm run build`，Chromium `scripts/analysis_view_filter_browser_smoke.cjs` 验证：同视图未映射时目标不联动，配置后只对明确目标维度查询，保存/重进/发布后的字段映射、跨视图值清除、读者只发送允许的两个参数。
- GitHub：`.github/workflows/analysis-view-filter-scoped.yml`。只有所触发 CI 真正通过才能将本批标记完成。定向之外不运行全量回归。

## 风险与后续

真实 MySQL / StarRocks 数据类型转换规则和跨多连接语义未纳入本批；严格同类型可拒绝部分语义上近似字段，属于故意保守设计。若源/目标数据集物理列被改名或删掉，即使旧快照仍保留 ID，读者也会拒绝该映射。服务器仍可能执行不同 Widget 的独立查询；**不是**多个数据集一次 SQL 的结果 JOIN，也不保证多个 Widget 读取同一数据库事务快照。

尚未实现：有权限的字段映射审批/元模型登记、跨数据集可视化关系管理、多字段多值联动、钻取路径和多视图调度。开发机目前离线，本批通过 GitHub API 提交并由 Actions 执行隔离定向测试，**没有**部署生产 `18110`、运行真实业务库或改变原 MetaDB。
