# P0 实战任务书：FineBI 式自助分析计算引擎（StarRocks 直连）

**状态：P0 核心代码开发中，尚未完成 G0/G1～G4 和 P0-05 真实环境验收；本文同时记录已实施改动与发布门禁。**

- 总体目标：回到 [总计划](../FINEBI_DEVELOPMENT_EXECUTION_PLAN.md)。
- 以现有仓库的 `8ac5a03` 为基线，P0 只覆盖字段模型、行级/聚合计算、前后过滤、多维多指标、可靠 SQL 编译、组件体验与真实 StarRocks 对账。
- 新建分支：`feat/finebi-analysis-engine-p0`（执行前检查工作区状态）。
- **不要在本任务中新增**数据抽取、异构跨源 JOIN、多用户权限底座、Datart 迁移、FineBI 专有 JAR、完整窗口同比环比、所有高级图表。
- 不修改 `/opt/FineBI6.0`；不要把演示数据写入 `ods` 业务库。

## 工作方式

执行顺序严格为 **P0-00 → P0-01 → P0-02 → P0-03 → P0-04 → P0-05**。每项做完在本文勾选并追加实际 commit/test/尚未完成，未完成则保持 `[ ]`。推荐同一小任务一个 commit，不允许“写了类/画了 UI”就勾选完成。

### P0-00 基线冻结和环境检查

- [ ] `cd /opt/insight-bi && git status --short && git branch --show-current && git log -1 --oneline`。有未提交代码先检查来源；不要覆盖。
- [ ] 创建功能分支 `git switch -c feat/finebi-analysis-engine-p0`（已有分支则先检查、切换，不重置）。
- [ ] 记录并保持旧 `POST /api/query`、`GET /api/datasets`、`GET /api/dashboards/{id}` 的行为、字段及响应格式。
- [ ] 读取 `BiApi.java`、`DatabaseStore.java`、`frontend/src/api.ts`、`AnalysisSubject.vue`、`ChartWidget.vue` 和已有 Java/Playwright 测试，证明目标缺口仍存在。
- [ ] 安全备份：现有 MySQL 仅 `insight_` 前缀元数据表、`insight-bi.service` 配置和旧 JAR；保存在服务器非 Git `runtime/` 中，权限 600；备份失败立即停止。禁止在日志、终端汇总中输出账户密码或连接密钥。
- [ ] 确定与生产隔离的测试端口（`ss -lntp` 先检查）；线上 `18110` 不被边改边重启；同机 FineBI `37799`、Datart `8087`、DataForge `8080`、预览 `18112` 保留。
- [ ] 明确测试数据来源：先用 Java 单测固定行集；真实 StarRocks 回归使用经过确认的独立测试库/现有非生产表，不创建/删除生产明细表。

**门禁 G0**：分支干净，旧接口自动化已可跑，元数据可恢复，P0 测试数据方案落实。未通过，不执行 DDL 或替换线上 JAR。

## P0-01 字段元数据 + 兼容层

**现状**：`Field {name,type,numeric}` 缺稳定 ID 和准确的分析逻辑类型；列表字段可能重新发现后漂移。目标是在不破坏已有仪表板的前提下引入 V2 字段语义。

### 开发动作

- [ ] 新建 Java 包 `backend/src/main/java/com/insightbi/analysis/`：
  - `model/LogicalType.java`：STRING、INTEGER、DECIMAL、DOUBLE、DATE、DATETIME、BOOLEAN、UNKNOWN；明确精度/scale、空值和时区。
  - `model/FieldDefinition.java`：`id,datasetId,sourceColumn,displayName,logicalType,role(DIMENSION|MEASURE|BOTH),nullable,physical|calculated,expressionId,hidden,ordinal`。
  - `field/FieldCatalogService.java`：物理列发现、同名/别名处理、字段 ID 查询、数据类型兼容与字段授权校验（即使 P0 仍单管理员，也要预留边界）。
- [ ] 制定迁移方案（MySQL 元数据库）：增量增加 `insight_dataset_field`（stable field_id / dataset_id / source_name / label / logical_type / role / ordinal / hidden）和 `insight_calculated_field`（id / dataset_id / name / expression_json / output_type / phase / revision / 时间）。字段 ID 在物理列刷新时尽可能沿用，不得每次随机重建。**先备份；迁移脚本幂等；禁止破坏旧 `fields_json`。**
- [ ] 兼容：`GET /api/datasets` 旧 `fields[{name,type,numeric}]` 必须保持；通过新 `GET /api/datasets/{datasetId}/fields` 输出 V2。缺乏 V2 时从旧数据读取并渐进升级，旧组件仍按字段名渲染。
- [ ] 提供按字段 ID 映射 SQL 安全标识符的唯一服务；同名显示标签不得决定数据库列引用。

**验收 G1**：字段类型可正确识别 MySQL/StarRocks DECIMAL、时间、整型、文本；同名标签/字段别名不串列；反复加载/刷新字段 ID 不变；旧四张主要元数据表和旧查询输出无变化。

**定向测试建议（新增后运行）**：`FieldCatalogServiceTest`、`LogicalTypeMappingTest`、`LegacyFieldCompatibilityTest`，配合旧 `BiQueryInTest`。

## P0-02 结构化查询 V2、SQL Planner、多维多指标

**现状**：`BiApi.query()` 当前直接拼字符串 SQL，数据合同只支持字符串型维度列表和简单 measures/filters。目标：将 Controller 变薄，以强类型 DTO 编译 SQL。不要让客户端发送 SQL 片段。

### 最小查询 V2 请求示例

```json
{
  "datasetId": "dataset_sales",
  "dimensions": [
    {"fieldId": "f_city"},
    {"fieldId": "f_date", "granularity": "month"}
  ],
  "measures": [
    {"id": "revenue", "fieldId": "f_revenue", "aggregate": "SUM", "alias": "收入"},
    {"id": "profit", "fieldId": "f_profit", "aggregate": "SUM", "alias": "利润"}
  ],
  "filters": {"pre": [], "post": []},
  "sort": [{"ref": "revenue", "direction": "desc"}],
  "limit": 200
}
```

请求中的 `fieldId` 只能来自后端此数据集元数据，`ref` 只能来自本请求已生成的维度/指标别名映射；`granularity` 必须是受支持的日期粒度。禁止提交原始 `WHERE` 字符串、任意 Java/SQL 表达式或外部连接凭据。

### 接口契约（拟新增，不改坏旧端点）

| 路径 | 输入/输出 | 要求 |
|---|---|---|
| `GET /api/datasets/{id}/fields` | V2 FieldDefinition 列表 | 与旧列表字段兼容 |
| `POST /api/analysis/v2/compile` | QueryRequestV2 → `columns, sqlPreview, bindCount, warnings` | **不实际执行查询**；只供作者访问，脱敏敏感参数 |
| `POST /api/analysis/v2/query` | QueryRequestV2 → `columns, rows, executionMs, truncated, warnings` | 后端检查后经 JDBC 查询、LIMIT/超时、返回类型信息 |
| 现有 `POST /api/query` | 原 V1 请求/响应 | 旧组件持续可用，必要时内部经 V1Adapter 转成 V2 |

### 代码拆分

- [ ] `analysis/model/AnalysisQueryRequest.java`、`QueryFilter.java`、`MeasureSpec.java`、`QueryResult.java`。
- [ ] `analysis/planner/AnalysisQueryPlanner.java`：解析字段、维度粒度、分组、多个 measure、聚合别名、排序/TopN/LIMIT 和阶段依赖；返回不可变逻辑查询计划。
- [ ] `analysis/sql/StarRocksSqlCompiler.java`：只接收结构化计划；字段标识符只从 FieldCatalog 引用；值使用 `PreparedStatement` 参数，不允许任意标识符和动态函数名输入。
- [ ] `analysis/service/AnalysisQueryService.java`：只读 JDBC 查询、超时、行数上限、结果 schema、耗时、脱敏日志。旧查询入口可经适配器转发但须保持响应兼容。
- [ ] 与 P0 需求相关的 SELECT SQL 数据集：`DatabaseStore.safeSql` 是现有规则过滤，不是严格 AST；P0 新增对可信 SQL 数据集的只读解析/语法验证，至少拒绝多语句、写入/DDL、危险调用及未批准函数。所有底层连接必须只读且最小权限；若解析器不支持某语法，应明确报错，不放行未知 AST。
- [ ] 控制上限：服务端限制字段数/表达式深度/查询超时/返回上限；默认遵守当前 500 行结果上限、100 行预览上限，不默默扩大。

### 查询编译示例（示意，不是生产表名）

```sql
SELECT
  biq.`city` AS `city`,
  DATE_FORMAT(biq.`order_date`, '%Y-%m') AS `order_month`,
  SUM(biq.`revenue`) AS `revenue`,
  SUM(biq.`profit`) AS `profit`
FROM (<经过只读校验的原始数据集 SQL>) AS biq
GROUP BY biq.`city`, DATE_FORMAT(biq.`order_date`, '%Y-%m')
ORDER BY `revenue` DESC
LIMIT ?
```

日期表达式及绑定类型以当前实际 StarRocks 版本测试为准；不得把表/列/排序/函数名当值参数插入 SQL。

**验收 G2**：两个维度、三个指标（不同聚合）自动生成正确 GROUP BY；TopN/排序字段合法；未知字段、错误类型、过长数组、非法原始 SQL 被拒绝；旧 `POST /api/query` 结果不变。

**测试**：`AnalysisQueryPlannerTest`、`StarRocksSqlCompilerTest`、`LegacyQueryAdapterTest`、`ReadOnlyQuerySecurityTest`。

## P0-03 公式 AST、计算阶段、前后过滤

### 规则（先写单元测试，再写解析和编译）

- **ROW**：`revenue - cost`，每行计算；可参与 `WHERE` 与后续 SUM。
- **AGGREGATE**：`SUM(profit) / NULLIF(SUM(revenue), 0)`，按当前维度动态聚合；不可等同 `AVG(profit/revenue)`。
- **POST_AGGREGATE**：结果级比较/过滤，通常编译为 `HAVING` 或外层查询；不改变原始分组集合。
- **WINDOW**：P0 只保留 AST 阶段/接口能力；同比环比、累计、排名等完整窗口函数归 P2，不得伪称 P0 完成。
- **NULL/除零**：可控 `NULLIF`、COALESCE 语义，不能将无意义的比率默默转成 0；小数精度、隐式转换规则必须显式定义。
- **字段引用**：使用稳定 `fieldId`，公式显示时可呈现字段名称；重命名显示标签不能让公式失效。

### 公式请求/返回（拟定）

```json
{
  "name": "利润率",
  "expression": "SUM([f_profit]) / NULLIF(SUM([f_revenue]), 0)",
  "phase": "AGGREGATE",
  "format": {"kind": "percentage", "decimals": 2}
}
```

```json
{
  "id": "calc_profit_rate",
  "phase": "AGGREGATE",
  "outputType": "DECIMAL",
  "canonicalAst": {"kind": "binary", "operator": "/", "left": {"kind": "call", "function": "SUM", "arguments": [{"kind": "field", "fieldId": "f_profit"}]}, "right": {"kind": "call", "function": "NULLIF", "arguments": [{"kind": "call", "function": "SUM", "arguments": [{"kind": "field", "fieldId": "f_revenue"}]}, {"kind": "literal", "value": 0}]}}
}
```

**注意**：上面的 `[fieldId]` 是公式语言的占位表示，不是 SQL 方言；服务器解析后先生成结构化 AST，才按受控字段/函数注册表编译，不能通过 `replaceAll` 拼 SQL。

### 拟新增 API

- `POST /api/datasets/{id}/calculated-fields/validate`：不保存，返回 canonicalAst、type、phase、错误位置；
- `GET /api/datasets/{id}/calculated-fields` / `POST .../calculated-fields` / `PUT .../calculated-fields/{fieldId}` / `DELETE .../calculated-fields/{fieldId}`：公式持久化、版本和引用检查；
- 编译/查询 V2 在 `filters.pre[]` 使用物理/行级字段；在 `filters.post[]` 使用聚合指标引用/受控表达式，类型/阶段不符返回 4xx。

### 代码任务

- [ ] `analysis/formula/FormulaLexer.java`、`FormulaParser.java`、`FormulaNode.java`、`FormulaTypeChecker.java`、`FormulaFunctionRegistry.java`。
- [ ] 仅允许首批函数：`+ - * / ABS ROUND IF/CASE COALESCE NULLIF SUM AVG COUNT MIN MAX`（逐一确认 StarRocks 支持/返回类型/NULL 语义）；COUNT DISTINCT 由明确 AST 选项实现。字符串/日期函数放后续小批，不要因范围扩大阻塞 P0。
- [ ] 错误必须返回字段/函数及位置，而不是数据库 JDBC 堆栈；禁止递归深度过大、循环公式引用、聚合内再嵌套非法聚合、访问未授权字段。
- [ ] `analysis/planner/FilterStageResolver.java`：分别编译 `filters.pre`（WHERE）与 `filters.post`（HAVING/外层）；区分作用于已汇总分组还是原始明细。
- [ ] SQL 数值参数、文本参数、日期参数均经 PreparedStatement 绑定；日期比较用日期类型，不能全部 String.valueOf 当文本比较。
- [ ] 将公式注册到 MySQL 元数据及依赖关系，删除被分析组件引用的计算字段时拒绝/给出使用位置。

**验收 G3**：负数、NULL、零、精度、聚合与行级差异、条件表达式、非法公式、循环引用、未授权字段、非法函数可重复测试；保存再读取公式 AST 一致。

### 用于正确性对账的最小测试数据（仅测试环境）

| 城市 | 日期 | 收入 | 成本 | 利润 |
|---|---|---:|---:|---:|
| 北京 | 2026-01-05 | 100 | 90 | 10 |
| 北京 | 2026-01-10 | 200 | 150 | 50 |
| 上海 | 2026-01-05 | 50 | 40 | 10 |
| 南京 | 2026-01-05 | 0 | -5 | 5 |

测试预期：
- 北京 `SUM(收入)=300`、`SUM(利润)=60`、利润率 `60/300=20%`，而逐行利润率的简单平均 = 17.5%。
- 明细前过滤 `收入 >= 150`：北京收入 200、利润 50；聚合后过滤 `SUM(收入) >= 150`：北京收入 300、利润 60。
- 南京分母 0 → 利润率为 NULL（后端保持空值类型，UI 显示可配置的占位）。
- 日期按月粒度聚合，北京 2026-01 一组；NULL、重复行补充专门用例，按业务是否需去重显式定义。

**测试**：`FormulaParserTest`、`FormulaTypeCheckerTest`、`CalculationPhaseTest`、`PrePostFilterTest`、`StarRocksFormulaE2ETest`（真实环境单独运行）。

## P0-04 组件分析编辑器（FineBI 使用体验）

**真实操作主线**：我的分析 → 新建分析主题 → 选数据集 → 左侧维度/指标树（支持查找）→ 中间字段槽 → 类型和属性 → 实时查询预览 → 底部组件切换 → 保存重进继续编辑。

### 页面/数据状态

- [ ] 将 `AnalysisSubject.vue` 拆分为可维护的小组件，建议 `frontend/src/analysis/FieldTree.vue`、`FieldShelf.vue`、`FormulaEditor.vue`、`ChartTypePicker.vue`、`AnalysisPreview.vue`、`useAnalysisQuery.ts`；避免再往单文件里继续堆逻辑。
- [ ] 新增 `AnalysisQueryV2`、`FieldV2`、`MeasureBinding`、`FilterBinding`、`CalculationBinding` 等 TS 强类型；V1 Widget 通过适配器读写，无损打开旧仪表板。
- [ ] 多维度与多指标字段槽：至少支持维度 1～3、指标 1～5；拖动换序、移除、别名/聚合/排序/粒度；数组 ID 保存，非单字段字符串覆盖。
- [ ] 字段树按数值/维度/日期组织，可搜索；计算字段从公式编辑器创建、验证、保存；选中后与普通字段一样绑定到指标槽。
- [ ] 图表类型切换时保留可兼容的字段绑定；不兼容要给提示与恢复操作，不可默默清空用户配置。
- [ ] 增量操作历史：组件新增/删除、字段拖放/移除和图表类型切换支持基本撤销/重做；保存前误操作可恢复，刷新后以已保存版本为准。
- [ ] 每个指标可配置 `series/type/axis/label/numberFormat`；共性图形选项与图表独有选项分开；新增配置必须真的作用于 ECharts，而不是仅存在表单。
- [ ] 实时查询由 V2 接口返回，用户手动触发或合理防抖；请求撤销/过期响应丢弃；加载、错误、空数据、截断标识可见。
- [ ] 保存到主题 `content_json` 采用 `analysisSchemaVersion: 2`（含 bindings、组件设置），保持 `widgets` 的旧版本降级读取；刷新/重新登录后不丢失公式和绑定。
- [ ] 不改变 `Designer.vue` 的已有 GridStack/Moveable 机制，本批仅保证计算出的分析组件可复用；主题内拖拽编排在 P1。

**验收 G4**：至少两组件，一张多指标柱图、一张利润率趋势图，均读取 StarRocks；切换组件、拖拽多字段、删除/撤销、保存刷新均正确；出现类型错误时前后端提示一致，旧分析主题和旧仪表板继续打开。

**浏览器测试**：在现有 `finebi_analysis_ui_smoke.cjs` 上新增 `finebi_multifield_ui_smoke.cjs`、`finebi_formula_ui_smoke.cjs`（**文件名仅是计划，执行前要确认脚本已创建**）。

## P0-05 真数对账、兼容、安全和发布

### 测试矩阵

| 编号 | 案例 | 预期 |
|---|---|---|
| E2E-01 | 维度城市/月、收入成本利润多指标 | SQL 分组/数值与基准一致 |
| E2E-02 | 行级利润公式和聚合利润率 | 与手工 SQL 对账；区别行级与聚合 |
| E2E-03 | WHERE vs HAVING 同门槛 | 北京输出 200 vs 300，可重复 |
| E2E-04 | NULL、零分母、负数、DECIMAL | 结果/类型一致，展示不崩溃 |
| E2E-05 | 排序/TopN/多系列 | 结果顺序正确，图例和系列绑定不串 |
| E2E-06 | 公式报错、字段越权/不存在、SQL 注入 | 返回明确的 4xx，无查询越权 |
| E2E-07 | 恶意 SQL、超时、超行数、只读 JDBC | SQL 拒绝/终止，日志不泄漏参数值 |
| E2E-08 | 保存并刷新重新进入 | 字段绑定/计算/样式/版本全保留 |
| E2E-09 | 旧 V1 请求与旧报表 | 旧查询与旧主题仍正确打开 |
| E2E-10 | 关闭新功能开关/回滚旧 JAR | 旧线上入口仍可用，元数据可读取 |

### 定向命令（在对应测试文件已经存在时执行）

```bash
cd /opt/insight-bi
# 旧接口回归 + 新增单测（仅涉及模块）
(cd backend && mvn -q -Dtest=BiQueryInTest,FieldCatalogServiceTest,AnalysisQueryPlannerTest,FormulaParserTest,PrePostFilterTest test)
# 前端类型检查 + 生产构建
(cd frontend && npm run build)
# 现有页面改动范围的交互测试
node scripts/finebi_analysis_ui_smoke.cjs
node scripts/finebi_workspace_ui_smoke.cjs
node scripts/finebi_public_data_ui_smoke.cjs
# 新测试脚本创建后才运行，禁止假装已经执行成功
node scripts/finebi_multifield_ui_smoke.cjs
node scripts/finebi_formula_ui_smoke.cjs
git diff --check
```

真实 StarRocks 对账应单独有命令/环境开关（默认不运行、不触碰生产数据）；测试输出只包括通过率、SQL 摘要和预期/实际数值，不得泄漏连接口令、完整带敏感值 SQL 或个人信息。

### 发布检查

- [ ] MySQL 迁移脚本 dry-run，备份完整、可恢复；新表/字段与当前数据兼容。
- [ ] P0 四个开发门禁 G1～G4 全通过；E2E-01～10 按实际支持度有证据，未通过记录阻塞项。
- [ ] 用独立测试环境运行 jar + 前端；确认空数据/错误页、旧仪表板及已发布版本正常。
- [ ] 再使用仓库当前的 `scripts/deploy.sh` 正式部署（只有经确认可发布时）；先备份 JAR/DB；确认 `18110/api/health` 与关键接口、其他系统均正常。
- [ ] 每个任务单独 commit、保持文档中的复选框状态；不得将半成品的结果称为“完整 FineBI 计算引擎”。

## P0 结束后如何开始 P1

完成 **计算后图表/表格能正确使用且持久化** 后，才进入：
1. 将分析主题扩展为：主题数据引用 + 可编辑步骤 + 多组件 + 多仪表板；
2. SQL 编译支持同源 StarRocks 中的 JOIN、UNION、条件列、分组汇总与逐步预览；
3. 把 `Designer.vue` 的 GridStack/Moveable 布局纳入分析主题，不另造第四套设计器；
4. 再完善全局筛选、跨图表字段联动、跳转、钻取、共享权限；
5. 复杂窗口/同环比和完整用户权限按独立阶段验收。

**P0 不包含 P1 项目，严禁为“功能看起来完整”而创建虚假管理按钮。**

## 执行记录（由下一位开发者持续填写）

| 日期 | 任务 | 分支/commit | 定向测试及结果 | StarRocks 对账 | 阻塞/下步 |
|---|---|---|---|---|---|
| 2026-10-13 | 编写本任务书 | 用 git log --oneline -- docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md 查询 | Markdown 链接、JSON 示例、现有接口交叉检查通过 | 不适用 | P0-00 待实施 |
| 2026-10-13 | 续修 P0-02/03/04：公式依赖及快照保护、只读函数白名单、V2 图表空值与查询取消 | feat/finebi-analysis-engine-p0: 4721a08、3c7b963、fc90416、0280e14、22bd28b、f75ad68、e7d7577 | GitHub Actions #32/#34 的 backend/frontend 均成功；新增 AnalysisFormulaDependencyTest、ReadOnlySqlGuardTest；未执行真实浏览器测试 | **未执行**（开发机离线） | G0 备份与隔离环境仍阻塞；P0-05 尚未验收，禁止直接部署 18110 |
| 2026-10-13 | P0-04 组件分析 UI 增量及开发机浏览器验收 | feat/finebi-analysis-engine-p0: 730c101、012cc5a、3f8d98f、a6ecd64、02c88eb | 隔离预览 npm ci --offline 与 Vue/TS/Vite 构建 PASS；Chromium 多字段拖放/撤销/重做/保存重开 PASS；公式错误/创建/绑定/重开 PASS；旧本地分支定向 Maven PASS | **未执行**，均为模拟 API | 运行服务实际使用 H2；生产 runtime/local.env 缺失；在线 H2 复制不是权威备份；G0/P0-05 仍阻塞，须先安全恢复环境、取得一致性备份及非生产 StarRocks 对账 |

| 2026-10-13 | P0-04 指标独立样式和真实浏览器复测 | feat/finebi-analysis-engine-p0: 715b3bf、e6322fb、acc3081、7e7951f、65eb6c8 | 两指标设置小数位/左右轴/标签可保存，样式修改不触发 SQL 重查；Chromium 两条脚本 PASS，离线 Vue+TypeScript/Vite 构建 PASS；Bash 部署脚本语法 PASS | **未执行**，测试 API 为模拟数据 | G4 完整图表/真数仍待验；G0 的凭据恢复、一致性备份、服务器 Git 同步受阻；P0-05 未通过 |

| 2026-10-13 | 最新后端隔离回归 / P0-05 发布门禁复核 | feat/finebi-analysis-engine-p0: e3deabe | 开发机隔离克隆 Maven 定向执行 BiQueryInTest、AnalysisQueryPlannerTest、AnalysisEngineSqlExecutionTest、AnalysisFormulaDependencyTest、ReadOnlySqlGuardTest 全部 PASS；GitHub Actions #86 前后端成功 | **未执行**，无非生产 StarRocks 连接凭据和受控测试数据对账 | 当前生产元数据为 H2（非计划中的 MySQL）；runtime/local.env 缺失且 GitHub HTTPS 超时，生产服务不能重启；先处理环境 G0 再推进 P0-05 |

| 2026-10-13 | P0-01/02/04/05 边界正确性、字段类型刷新、去重计数与只读 E2E 入口 | feat/finebi-analysis-engine-p0: d9c9ff3、7dc64c7、9b6430c、dc56ae6、fc12cdb、532fcf1、b76a25e、26523e7 | 开发机 Maven 定向测试 7 组 PASS、Vue/Vite build PASS、Chromium 多字段及公式两条脚本 PASS；E2E 只读 Runner 语法 PASS、未授权拒绝 PASS | **未执行真实 StarRocks**：JUnit E2E 默认跳过，需要明确非生产只读 JDBC 账号和开关 | G0：生产实际 H2、runtime/local.env 缺失、H2 一致性备份未完成；P0-05 禁止认定完成 |

| 2026-10-13 | P0-02/03/04 公式 CASE+canonical AST、API 类型与长度校验、查询并发及前端公式组件拆分 | feat/finebi-analysis-engine-p0: 0ffecdc、9c84d5d、2b7c7f9、1d76e89、9b041b8、04b6dda、fb13fa8 | 仅运行 FormulaCompilerTypedTest、AnalysisFormulaValidationTest、AnalysisQueryLimiterTest 的相关单测；公式 UI TypeScript/Vite build 与单项 Chromium smoke PASS；不做整站回归 | **未执行**；现有独立 StarRocks E2E 仍需只读非生产环境 | G0/P0-05 保持阻塞：生产 H2 一致性备份和本机 runtime/local.env 待安全恢复，未重启 18110 |

| 2026-10-13 | P0-01 数据集 Schema 更新前防护、显式 SQL 别名迁移、字段隐藏、元数据库幂等迁移 | feat/finebi-analysis-engine-p0: 6dbbd1a、917897c、664dd0d、19f1506、712d9be、e3dec82、1e3e586、02cffd1、3346d64 | 只运行 DatasetSchemaGuardTest（5）、DatasetAliasUpdateIntegrationTest（1）、FieldVisibilityTest（2）及受影响前端 build；Chromium 别名映射与隐藏字段 UI 两条脚本 PASS；CI 变更范围脚本语法与模拟 Git diff PASS | **未执行真实 StarRocks**，仅 H2 与模拟 REST | G0 生产配置恢复/一致性备份未完成；G4/P0-05 不得标记通过，生产 18110 不变 |

| 2026-10-13 | P0-05 前置收尾：V1 旧列名兼容、V2 JDBC 强制只读、H2 临时库恢复演练、发布前证据闸门与停服前打包 | feat/finebi-analysis-engine-p0: dc98b05、687e2ca、818e6de、461fa6a、39e9912、2bec97d、de81e52、c107076、55f6832、7bfa30e、e0dac56、2f3de7b | 仅运行 DatasetSchemaGuardTest（8）、ReadOnlyConnectionPolicyTest（2）、MetadataRecoveryRehearsalTest（1）定向 PASS；synthetic p0_release_safety、无授权/无报告拒绝、bash -n PASS | **真实 StarRocks 未运行**，JUnit 仍 skip；专用非生产只读账号测试待完成 | **不得发布**：生产 local.env 缺失、生产 H2 未取得经恢复验证的一致性备份、隔离生产式验收/回滚未完成；见 docs/P0_RELEASE_GATE.md |

每推进一项，在根目录总计划和本表中同步更新状态。首次任务必须是 **P0-00**，后续任务不得跳过依赖关系。
