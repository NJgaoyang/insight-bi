# Insight BI · P1 实施状态

> 2026-10-13 · P1-01 **增量开发中**（仅实现主题资源图和共享组件多视图，不代表 P1-01 全部完成）。
> 使用分支 `feat/finebi-analysis-engine-p0`；生产 `18110` 没有部署本次功能。

## 执行安排

根据使用者指示，**DataForge StarRocks 真实对账暂缓**，继续执行不依赖该连接的后续开发。原 P0-05、G4 以及生产 H2 元数据一致性备份/回滚门禁维持未通过；本轮 P1 代码不会因此直接发布生产。

## P1-01 第一批：主题、组件和数据集结构化引用

- 新增 `analysis/AnalysisResourceGraph.java`，从主题 `content.widgets[]` 提取稳定组件 ID 和实际引用的多数据集 ID，支持兼容 V1 和 V2 组件配置，不以标题、备注或整个 JSON 字符串匹配判断数据集引用。
- 新增 `GET /api/dashboards/{id}/resources?published=false|true`：报告 `datasetIds`、`widgetIds`、`widgetCount`、`dashboardIds`、`dashboardCount` 以及 `missingDatasetIds`；读接口不会执行数据仓库 SQL。
- 分析主题保存时验证组件 ID 唯一、数据集确实存在，忽略客户端伪造的 `resourceIndex` 并重新生成；发布前复核引用，无效引用不发布。普通旧仪表板保持原 JSON 写入合同。
- 数据集删除改为结构化校验，覆盖 **草稿、已发布快照和历史版本**；标题误含数据集 ID 不再误阻止，无法读取的旧快照则安全拒绝，不新增表或修改已有历史快照。

## P1-01 第二批：同一主题下多个仪表板视图

- 采用可选、向后兼容的 `content.dashboards: [{id,name,widgetIds}]`；一个主题可配置 **1–30 个视图**，不同视图共享 `widgets[]`，不复制查询绑定或图表定义。
- 后端检查视图 ID 唯一、名称非空、`widgetIds` 全部是本主题现存组件且同视图不重复；禁止保存悬空引用。资源索引附带 dashboard view ID，版本发布保留视图配置。
- `AnalysisSubject.vue` 在预览区新增视图切换、新建、重命名、删除和已有组件多选；删除视图不会删除复用组件；新建组件自动纳入当前选中的视图；删除组件从所有视图剔除其 ID；保存和撤销/重做快照包含视图数据。
- 旧主题没有 `dashboards` 时，在编辑器内用单一默认视图呈现所有旧组件；首次保存才写入新的 `dashboards` 属性。旧普通仪表板编辑链路不强制升级。
- UI 遵循简洁中文企业产品风格，仅加紧凑选择器和操作控件，不加入介绍卡片或虚假数据。

## P1-01 第三批：已发布分析主题多视图查看

- 目录中的“查看”过去统一打开 `Designer.vue`，只能按旧 V1 组件渲染。新增独立 `PublishedReport.vue`：先加载 `GET /api/dashboards/{id}?published=true`，发布对象属于分析主题时，按 `content.dashboards[]` 的引用在只读页面显示 `AnalysisV2Chart` 或兼容 `ChartWidget`；提供紧凑的已发布视图切换选择器，不提供保存、更名或删除权限。
- 普通仪表板仍交由既有 `Designer.vue readonly` 处理，不替换原画布或重写历史数据。
- 若是旧分析主题发布快照且没有 `dashboards`，自动以单一视图展示全部组件；新旧数据没有强制迁移。
- 新增 `finebi_published_views_ui_smoke.cjs`，隔离 Chromium + 模拟 API 验证已发布 V2 的两个视图和原普通仪表板只读路由，**PASS**。
- 新增 `AnalysisResourceGraphTest` 的版本恢复用例：通过既有 `/versions/{version}/restore` 恢复多视图配置和稳定组件/数据集引用，**PASS**。

## P1-01 第四批：视图独立布局、宽高与排列

- 视图 `content.dashboards[].layout` 新增可选字段，按组件 ID 保存 `{columns: 6|12, height: 320|480|640}`；每个视图可有不同排序 `widgetIds[]`、宽度和高度。图表 SQL、数据集、指标配置继续只存在共享的 `widgets[]` 中，不会因布局变化被复制。
- `AnalysisResourceGraph` 保存与发布时严格校验：布局只能引用此视图实际选中的组件、宽高只能是允许的整数档位，防止越界、溢出和悬空 ID；无 `layout` 的历史主题继续采用原 380px 默认高度，不做数据迁移。
- `analysis/dashboardLayout.ts` 提供有序组件列表、默认尺寸以及清理废弃布局引用。分析主题预览中新添紧凑的组件前移/后移、半宽/全宽、三档高度控件；从当前视图移除组件或全局删除组件后清理布局引用。
- `PublishedReport.vue` 的 V2 已发布查看页按保存的视图顺序/尺寸只读渲染；窄屏切换为单列。既有普通仪表板 `Designer` 的保存与查看路径没有更改。
- 版本恢复仍保留完整 `dashboards[].layout`，发布后的快照不会随草稿中布局的变化而被直接覆盖。

### 只验证了本次关联范围

- `mvn -q -o -Dtest=AnalysisResourceGraphTest test`：**8/8 PASS**，包含独立布局保存/发布/历史恢复、异常引用和整数溢出拒绝。旧普通仪表板保存合同没有更改。
- `npm run build`：`vue-tsc --noEmit` 与 Vite **PASS**。
- `node scripts/finebi_view_layout_ui_smoke.cjs`：真实 Chromium 交互、模拟 REST **PASS**；验证多视图布局互不影响、排序宽高保存、刷新、发布查看、响应式窄屏。
- 对修改涉及的页面额外只运行 `finebi_theme_views_ui_smoke.cjs` 与 `finebi_published_views_ui_smoke.cjs`，**均 PASS**。未做整站或全量 Maven 测试。
- 生产 `18110/api/health` 返回 `{"ok":true}`，工作树 0 改动，测试预览 `18117` 已关闭。未部署生产。

## P1-02 第一批：可保存、有实际 SQL 的顺序数据加工

- 新建 `AnalysisPipelineCompiler`，支持**计算列、明细过滤、一次分组汇总** 3 类有序步骤。过滤可在分组前或汇总后继续执行，SQL 编译顺序取决于步骤位置；原始数据源只接受经过 AST/函数白名单验证的只读 SELECT。
- 公式仅允许 ROW 阶段，使用现有 `FormulaCompiler` 的受控字段 ID 与表达式 AST，不执行用户任意 SQL 字符串；条件值统一转 JDBC 参数。分组支持最多 3 维度、5 指标和 COUNT(*) / SUM / AVG / COUNT / MIN / MAX，聚合后仅能引用当前步骤结果字段。不重复聚合，不跨数据集写入；每条流水线最多 12 步。
- 新增 `AnalysisPipelineApi` 接口：
  - `POST /api/datasets/{id}/pipeline/compile`：只编译 SQL、列与参数数量，不接触数据仓库。
  - `POST /api/datasets/{id}/pipeline/preview`：从现有数据连接执行只读查询，强制 JDBC 只读、并发/超时、最多 200 行和溢出哨兵；异常时不泄露用户密码。
  - `GET /api/datasets/{id}/pipeline`：获取步骤列表、版本号，以及原 SQL 变化后产生的 `stale` 状态。
  - `PUT /api/datasets/{id}/pipeline`：先编译校验再按 revision 保存，避免覆盖其他编辑者的修改；SQL 源变化时旧配置禁止直接预览，必须重新校验保存。
- 数据库只新增独立的 `insight_dataset_pipeline` 表（`dataset_id`, `config_json`, `source_sql_hash`, `revision`, `updated_at`），`CREATE TABLE IF NOT EXISTS` 幂等，删除数据集时同事务清理。没有修改既有数据集 SQL 或仪表板 JSON；旧版本服务不依赖该表。
- `PipelineEditor.vue` 新增简洁数据加工页面，从“公共数据 → 加工”进入。支持步骤增加/删除/前移/后移、基本字段与运算符设置、SQL 预览、真实数据预览、保存并重新进入恢复。入口只修改 App 的相关路由，不影响旧数据集编辑。
- **本批暂未实现**：同源 JOIN / UNION、多个分组汇总、复杂条件列 UI 以及加工结果作为其他分析主题的直接可复用数据集；不将 P1-02 整阶段标记完成。
- **StarRocks 真数依照用户指示暂缓**，隔离 H2 测试只能证明 SQL 编译与参数语义，不可替代目标数据库 E2E。

### 本批定向测试证据

- `mvn -q -o -Dtest=AnalysisPipelineCompilerTest,AnalysisPipelineApiTest test`：**6/6 PASS**（4 个有序 SQL/H2 语义、2 个保存/陈旧源检测和 JDBC fail-closed）。
- `npm run build`：Vue 3/TypeScript 与 Vite **PASS**。
- `node scripts/finebi_pipeline_ui_smoke.cjs`：隔离 Chromium 浏览器 + 模拟 API，**PASS**。验证页面进入、公式/过滤/分组步骤拖前后次序、SQL 编译、数据预览、CAS 保存、重新加载恢复。
- CI `scripts/p0_scoped_ci.sh` 已配置仅在加工编译器/API 或相关元数据迁移发生变更时运行这两组测试；**未执行全量回归**。
- 生产 `18110/api/health` 为 `{"ok":true}`、`insight-bi.service` active、生产 Git 工作树 0 改动；预览 `18117` 已关闭。**未部署生产、新增元数据表未在生产执行**。

## 本轮仅测试受影响模块

- 开发机隔离路径：`/opt/insight-bi-p0-preview`。
- `mvn -q -o -Dtest=AnalysisResourceGraphTest test`：**7/7 PASS**。覆盖多数据集、视图引用、保存/发布索引、历史数据集删除保护、旧仪表板合同、缺失和伪造引用。
- `mvn -q -o -Dtest=AnalysisResourceGraphTest,FineBiWorkspaceFolderTest test`：**成功**，附带只检查相关旧工作区合同。
- `npm run build`：Vue 3 / TypeScript / Vite **成功**。
- `node scripts/finebi_theme_views_ui_smoke.cjs`：隔离 Chromium 真实页面操作 + 模拟 REST，**成功**。验证两个视图共享组件、选择/重命名/保存/刷新恢复和删除视图不删组件。
- `node scripts/finebi_published_views_ui_smoke.cjs`：隔离 Chromium 通过模拟 REST 验证**真实已发布查看路由**（V2 多视图切换与原 V1 Designer），**成功**。
- 版本恢复测试覆盖：`AnalysisResourceGraphTest` **7/7 PASS**，含从旧版本恢复主题多视图与组件 ID。
- `bash -n scripts/p0_scoped_ci.sh` 和 `node --check scripts/finebi_theme_views_ui_smoke.cjs`：**成功**。CI 的文件变更测试映射包括 `AnalysisResourceGraphTest`。
- 未运行全量回归；没有进行 StarRocks 真数或生产发布。

## P1-01 未完成

- 更正式的主题资源元数据层（包括多主题复用组件与权限隔离）；
- 主题内仪表板视图的布局方案与独立发布/回滚策略（当前多个视图随主题整体一起保存和发布）；
- P1-02 数据编辑步骤引擎（行公式、过滤、聚合、同源 JOIN/UNION 的可组合步骤）；
- P1-03 现有可视化设计器集成（画布布局、联动、下钻、发布快照）。
- P0-05 DataForge StarRocks 实际 JDBC 对账、生产 H2 一致性备份和生产式回滚演练。

**下一步建议按计划进入 P1-01 的视图布局/版本依赖，再推进 P1-02；未完成项目不勾选通过。**
