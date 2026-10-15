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
- 计算列编辑器提供当前可用字段的**中文名称选择按钮**，点击插入后端稳定 `[fieldId]`，无须手工查找随机 UUID；现有步骤重排和已有字段的合法性最终仍由后端编译器校验。
- **本批暂未实现**：同源 JOIN / UNION、多个分组汇总、复杂条件列 UI 以及加工结果作为其他分析主题的直接可复用数据集；不将 P1-02 整阶段标记完成。
- **StarRocks 真数依照用户指示暂缓**，隔离 H2 测试只能证明 SQL 编译与参数语义，不可替代目标数据库 E2E。

### 本批定向测试证据

- `mvn -q -o -Dtest=AnalysisPipelineCompilerTest,AnalysisPipelineApiTest test`：**6/6 PASS**（4 个有序 SQL/H2 语义、2 个保存/陈旧源检测和 JDBC fail-closed）。
- `npm run build`：Vue 3/TypeScript 与 Vite **PASS**。
- `node scripts/finebi_pipeline_ui_smoke.cjs`：隔离 Chromium 浏览器 + 模拟 API，**PASS**。验证页面进入、公式/过滤/分组步骤拖前后次序、SQL 编译、数据预览、CAS 保存、重新加载恢复。
- CI `scripts/p0_scoped_ci.sh` 已配置仅在加工编译器/API 或相关元数据迁移发生变更时运行这两组测试；**未执行全量回归**。
- 生产 `18110/api/health` 为 `{"ok":true}`、`insight-bi.service` active、生产 Git 工作树 0 改动；预览 `18117` 已关闭。**未部署生产、新增元数据表未在生产执行**。

## P1-02 第二批：条件列 + 同连接上下合并（2026-10-14）

继续按执行计划实现**有序、可保存、后端真实编译的加工步骤**，并遵守用户“只测试修改部分”的要求。

### 条件列

- 后端新增 `case` 步骤：源字段 `sourceFieldId`、比较条件、结果列 `fieldId`、输出类型 `STRING/DECIMAL`、满足与不满足条件的值，编译为 `CASE WHEN ... THEN ? ELSE ? END`，仅允许白名单操作符。
- 新列与源字段使用不同 ID，数值分支进行类型校验；所有比较值和输出值通过 JDBC 参数绑定。**外层 SELECT 的条件列参数必须排在内层 FROM 子查询参数之前**，已通过 H2 实际执行验证。
- 支持条件列参与后续过滤、分组，分组之后新增条件列则仅可选择汇总后仍可见的字段。前端提供中文条件列编辑表单并参与步骤保存/恢复。

### 同连接 UNION ALL

- 新增 `union` 步骤：通过 `sourceDatasetId` 选择另一份已保存数据集；后端只根据真实元数据读取 SQL，拒绝客户端任意 SQL、相同数据集、跨连接、列名/字段数/类型不兼容等情况。
- 第一批限制：**仅允许在第一步执行、一次合并、字段物理名称和逻辑类型一致**；明确采用 `UNION ALL`，保留重复记录，允许后续计算列/过滤/分组。JOIN、多数据源组合和自动字段映射尚未实现。
- 额外数据集的只读 SELECT 仍经过 `ReadOnlySqlGuard`，投影列由字段目录安全生成；不会创建数据库表或修改业务数据。保存的配置源指纹同时包含第二份数据集的 SQL/结构，关联 SQL 修改后返回 stale 并拒绝旧配置预览。
- 前端“上下合并”仅显示同连接候选数据集、自动放在第一位，禁止移动到后面。现有公共数据、仪表板和旧加工配置保持兼容。

### 本批定向验证（隔离开发机）

- `mvn -q -o -Dtest=AnalysisPipelineCaseTest,AnalysisPipelineUnionTest,AnalysisPipelineCompilerTest,AnalysisPipelineApiTest test`：**13/13 PASS**（CASE 4、UNION 3、既有 Compiler 4、Api 2；均无跳过）。
- `npm run build`：TypeScript 类型检查 + Vite 构建 PASS。
- `node scripts/finebi_pipeline_ui_smoke.cjs`：Chromium 页面操作 + 模拟 REST PASS，新增条件列/同连接上下合并的创建、固定顺序、保存和重开。
- `scripts/p0_scoped_ci.sh` 增加两个新测试类的按变更触发映射，`bash -n` PASS。
- **没有做全量回归、没有执行 DataForge StarRocks 真库对账、没有部署生产 `18110`。** 本阶段 H2 与模拟 API 结果不代表 StarRocks 实库验收。

### 仍未完成

P1-02 的同连接 JOIN（重名字段与多对多语义）、多源 UNION 映射与去重可选项、多级汇总以及加工结果作为分析主题可复用数据集仍待实现。P0-05/G4 的真实 StarRocks 与生产备份、恢复及发布门禁继续暂缓/未通过。

## P1-02 第三批：同连接多键 JOIN 与加工依赖删除保护（2026-10-14）

- 处理步骤新增 `join` 类型：从同一个数据连接中的已保存数据集选择右表，只允许 `INNER` 或 `LEFT`、**1–4 组等值条件**，必须位于第一步。数据连接和源 SQL 只能通过服务端元数据取得，禁止客户端直接提交任意 SQL、跨连接引用、CROSS JOIN。
- 右表必须显式选择 **1–30 个输出字段**，赋予新的稳定 `fieldId`，输出物理列统一采用 `join_<fieldId>` 别名。左表字段名称保持不变，避免 SELECT * 带来的同名字段覆盖；关联键类型必须一致、键组合不可重复，右侧输出字段与已有字段 ID 不得冲突。
- 由于无法在不实际扫描数据的情况下证明关联键唯一，**必须显式确认多对多/重复键会使行数放大的风险**；系统不擅自去重、不修改原业务表。支持 JOIN 后过滤、计算、分组汇总；仅支持本轮清晰定义的等值语义。
- 编辑页提供选择关联数据集、连接方式、多条件、右表输出字段与“行数放大风险”复选框；新增关联键时自动优先选取尚未使用且名称/类型匹配的字段组合，避免重复键默认阻塞保存。保存/重开保留稳定字段 ID。
- 原 UNION 指纹计算规则保留向后兼容，JOIN 则独立记录右表 SQL/物理列元数据；被引用的数据集 SQL/结构发生变化时，已有加工配置被标记 stale，重新校验保存之前不允许直接预览。
- 新增 `AnalysisPipelineDependencyGuard` 并接入现有数据集删除事务：当目标数据集正被其他数据集的 JOIN/UNION 加工引用时拒绝删除；删除主数据集同时清除其自身加工配置。只按结构化 `sourceDatasetId` 识别真实依赖，不因文字备注误拦；配置损坏时安全拒绝删除。

### 本批只测试受影响部分

- `mvn -q -o -Dtest=AnalysisPipelineJoinTest,AnalysisPipelineUnionTest,AnalysisPipelineCaseTest,AnalysisPipelineCompilerTest,AnalysisPipelineApiTest test`：**17/17 PASS**。覆盖 JOIN 多键、LEFT/INNER、未匹配行、重复键放大、JOIN 后过滤汇总、跨连接拒绝、类型/字段 ID 校验，并验证旧加工 SQL 的相关行为。
- `mvn -q -o -Dtest=AnalysisPipelineDependencyGuardTest test`：**3/3 PASS**，覆盖引用保护、删除清理、损坏配置和文本误判。
- `npm run build`：`vue-tsc --noEmit` 与 Vite **PASS**。
- `node scripts/finebi_pipeline_join_ui_smoke.cjs`：隔离 Chromium **PASS**，验证多键、右表字段、显式风险确认、保存及刷新恢复；`finebi_pipeline_ui_smoke.cjs` 原加工功能相关浏览器测试 **PASS**。
- `p0_scoped_ci.sh` 已补充 JOIN 和删除依赖保护的按文件触发测试映射；**未执行全量回归**。
- **尚未验证 StarRocks 真库 JOIN 执行**，继续按用户要求暂缓；生产 `18110` 未部署、新增功能未实际用于生产。

### 继续开发的未完成项

P1-02 仍需考虑多源字段自动映射、JOIN 键唯一性验证/数据量评估、多级汇总、处理后的数据作为主题可复用数据集、复杂类型兼容矩阵。P0-05/G4、生产 H2 一致性备份恢复和生产发布回滚演练依旧未通过。

## P1-02 第四批：加工结果复用到分析主题（2026-10-14）

- 分析主题 V2 图表新增可选 `analysisV2.usePipeline=true` 与 `pipelineRevision`，在“数据来源”中切换“原始数据 / 加工结果”，无需将加工结果复制或物化到 StarRocks。启用时从 `GET /api/datasets/{id}/pipeline/fields` 获取**真实保存后**的加工字段（含条件列、计算列、JOIN 右侧输出及分组指标），并在保存主题 JSON 时保留版本。
- `AnalysisPipelineApi.reusableSource` 校验：该数据集有已保存且非空的加工步骤、依赖 SQL/字段指纹未过期、绑定版本正好等于当前加工 revision；根据经过白名单验证的实际步骤构造可复用只读 SQL 与稳定字段目录，剥离只用于加工预览的 `LIMIT ?` 哨兵。不会执行用户传入的任意 SQL。
- `AnalysisApi` 的 `/analysis/v2/compile` 与 `/analysis/v2/query` 对 `usePipeline` 显式选择加工结果时，先将加工数据集 SELECT 作为只读子查询，后使用现有维度、指标、过滤与 Top N 查询规划；严格按**加工占位符在前、图表过滤与最外层 LIMIT 参数在后**合并 JDBC 参数，仍保留只读连接、最大行数和并发限制。未选择加工的原 V2 请求保持旧查询行为。
- `AnalysisResourceGraph.requirePinnedPipelines` 已接入分析主题保存、发布前检查：当前加工配置版本变化或原/关联数据集 SQL 已变化时拒绝保存/发布损坏的绑定，不改写已发布旧快照。更新加工版本必须在编辑器中明确“更新版本”并重新选择维度/指标。旧发布版本仍保留其原始版本号，不会静默切换成新版加工结果。
- UI 只增加紧凑的加工数据源选择开关、绑定版本显示/更新和过期提示；切换模式清理原字段槽位，避免旧字段 ID 被误用于不同来源。加工模式不显示原始字段的重命名、隐藏和原始公式编辑按钮；已发布只读 V2 图表传递同样的版本参数。
- 只增加独立 `AnalysisPipelineReuseTest`、`AnalysisPipelineSnapshotBindingTest`、`finebi_processed_source_ui_smoke.cjs` 与按文件定向 CI 配置，不修改现有数据集表结构，也没有创建生产数据表。

### 本批定向验证结果

- `mvn -q -o -Dtest=AnalysisPipelineReuseTest,AnalysisPipelineSnapshotBindingTest,AnalysisResourceGraphTest test`：**13/13 PASS**；针对实际 `/analysis/v2/query` 新增 JDBC 参数顺序、只读和连接释放测试后，只运行 `AnalysisPipelineReuseTest`：**4/4 PASS**（最终合计相关测试 **14/14 PASS**，没有执行全量 Maven 回归）。
- `npm run build`：Vue 3 / TypeScript 类型检查及 Vite 构建 **PASS**；`node scripts/finebi_processed_source_ui_smoke.cjs`：独立 Chromium 操作 + 模拟 REST **PASS**，覆盖选择加工字段、绑定版本、保存重开、版本变更、显式重新绑定及切回原始字段。
- `ProcessedAnalysisStarRocksReadOnlyTest` 已编写、编译并默认跳过。开发机对测试 FE 的 `3306` 与 `9030` TCP 均可连接；但注入凭据启动 JDBC 测试时被工具安全检查阻止，因此**尚无真实 StarRocks SQL 对账结果**。已清理临时凭据文件；不会伪称通过，也未尝试绕过检查。此测试仅使用内联 SELECT 数据，外部 StarRocks 无 DDL/DML。
- 已添加 `.github/workflows/p1-processed-analysis-scoped.yml` 和 `scripts/p0_scoped_ci.sh` 映射，仅执行关联 H2/合同用例及前端类型构建；没有添加任何数据库秘密到 GitHub。
- 生产 `18110` 未部署本次修改；P0-05/G4 真库、生产 H2 备份恢复与发布回滚门禁仍未通过。进一步进度以此节为准，不把 P1-02 其它 JOIN 扩展、多级汇总和高阶数据处理项标记完成。

## P1-02 第五批：UNION 显式列映射（2026-10-14）

- 后端加工步骤 `union` 新增可选 `mappings: [{leftFieldId,rightFieldId}]`。不同物理列名的数据集可经字段目录一对一映射后执行 `UNION ALL`，输出列名和稳定字段 ID 仍使用左表定义。
- 服务器校验：映射覆盖全部左侧物理字段、不得重用左侧/右侧字段、ID 必须属于实际数据集目录、逻辑类型严格相同。数据源继续限制为当前连接上的已有 SELECT 数据集，值与列名均不接受原始客户端 SQL。
- 未指定 `mappings` 的历史加工步骤保持原有按物理列名精确匹配行为，不触发配置迁移；`UNION ALL` 继续保留重复数据。本批先交付编译器和定向 H2 执行测试，**前端字段映射选择器尚未实施**，真实 StarRocks 对账亦未实施。
- 定向 CI 见 [P0 Changed-Area Checks](https://github.com/NJgaoyang/insight-bi/actions?query=branch%3Afeat%2Ffinebi-analysis-engine-p0)，测试结论以对应 commit 的实际工作流为准；不宣称未完成的 CI 通过。
- 生产 18110 未部署，生产 H2 备份、回滚和 P0-05 仍为发布阻塞项。

## P1-02 第七批：JOIN 右表关联键重复风险诊断（2026-10-14）

- 新增 `POST /api/datasets/{id}/pipeline/join-key-profile`，由服务端解析现有 JOIN 步骤并复用编译器验证键、类型、关联源及同连接限制。
- 在源数据库上执行带 10 秒超时的只读聚合：统计右表重复关联键组数 `duplicateGroups` 和右表重复组中超出首行的记录数 `extraRightRows`。无 INSERT/UPDATE/DDL，配置保存接口不触发扫描。
- 前端关联编辑器新增按需检查按钮和统计结果；条件修改时清空旧统计。该诊断**不是最终 JOIN 行数预测**，不取消已有行数放大确认机制；连接拒绝只读、超时或查询失败时不报告成功。
- 增加关联键重复的 H2 定向单测，测试与前端编译以 GitHub Actions 对相应提交的最终结果为准。未直接验证 StarRocks，生产 18110 未部署。

## P1-02 第八批：两级顺序分组汇总（2026-10-14）

- 有序数据加工支持最多两次 `group` 步骤。第二级只可引用第一级汇总产生的维度/指标，实际生成嵌套 SELECT + GROUP BY；可实现先按城市求和、再跨城市合计。第三次汇总明确拒绝。
- 修复加工编辑器一级汇总后的字段物理别名推导（维度 `d_<fieldId>`、指标 `m_<measureId>`），第二级字段选择与后端实际输出保持一致。
- 定向新增 H2 SQL 执行与三级拒绝用例，实际是否通过以相关 GitHub Actions 为准；未执行全量回归或真实 StarRocks 对账，未部署生产。

## P1-02 第九批：数据过滤操作补齐（2026-10-14）

- 数据加工 `filter` 编辑器支持 `gt/lt` 大于小于和多值 `IN` 过滤，使用标签式多值输入。单值与 IN 之间切换会转换或重置相关参数，旧的单值步骤维持兼容。
- 后端既有受控 `IN` 编译与 1～30 值上限继续生效，值均经类型转换后以 JDBC PreparedStatement 绑定；本次没有引入任意 SQL 字段或客户端表达式。
- 补充 `AnalysisPipelineCompilerTest` 的实际 H2 多值筛选、SQL 绑定顺序与空集合拒绝用例。相关 GitHub Actions 前端构建和后端定向测试以对应提交工作流的最终结果为准。
- P1-03、高级图表/权限治理、生产 H2 一致性恢复、StarRocks 实库验收和正式部署均仍未完成，不以当前增量提交替代整套实施计划验收。

## P1-02 第十批：UNION 去重模式（2026-10-14）

- `union` 步骤增加可选布尔 `distinct`，缺省/false 保持原来的 `UNION ALL`，true 改用 SQL `UNION` 对全部投影列去重；前端在上下合并步骤中提供勾选入口。
- 映射配置、同连接身份校验、只读源 SQL 以及现有保存/恢复协议继续沿用；非法非布尔标记会被拒绝。不引入自动按单列去重，避免与数据库完整行去重语义混淆。
- 新增 H2 定向测试验证默认保留重复、开启后消除整行重复、非法配置拒绝。以前后端 scoped CI 实际结论为准；StarRocks 真数、生产 H2 一致性备份和正式部署仍未通过验收。

## P1-02 第十一批：条件列 IN 多值匹配（2026-10-14）

- 加工编辑器条件列增加多值 IN 选择，支持 1～30 个按字段类型转换的绑定值；切换单值和多值操作时处理旧参数，不改变原条件列和历史配置的读取。
- 复用既有后端白名单 predicate 与 JDBC 占位符；新增 H2 定向测试验证 SQL 参数顺序、CASE 输出结果和空 IN 拒绝。
- 本批 CI 以具体提交结果为准。未进行 StarRocks 真数对账、整站浏览器回归或生产部署。P1-03、P2/P3 与发布门禁仍未完成。

## P1-03 第一批：组件跨视图跳转（2026-10-14）

- 复用现有分析主题 `dashboards[]` 多视图和独立布局；每个视图可配置 `jumps: {widgetId: targetViewId}`，不新建画布或元数据表。编辑器组件工具栏可选目标视图，已发布只读页通过组件按钮在本主题视图内跳转。
- 服务端 `AnalysisResourceGraph` 校验跳转源组件属于当前视图，目标是同主题其他现存视图；支持引用尚未遍历到的视图，拒绝悬空/自引用/跨视图组件。移除组件或删除视图时清理无效链接。发布快照保留跳转配置并独立于草稿。
- 新增 `AnalysisResourceGraphTest` 定向用例，验证跳转发布及无效引用拒绝。此前 P1-01 视图/布局能力已经存在，本批没有重新实现。**本功能为明确的组件按钮跳转，不宣称已实现图表数据点联动、钻取或跨页面动态筛选。**
- CI 只运行受改动影响的 Java/前端模块；未执行全量回归，真实 StarRocks、生产 H2 备份恢复和发布门禁仍未满足。

## P1-03 第二批：发布图表点击跳转（2026-10-14）

- 复用第一批发布快照 `dashboards[].jumps`，在 V2 ECharts 图形元素点击、指标卡点击、明细表行点击时触发视图内跳转。只在当前视图为组件配置有效目标时开放，未配置时沿用原图表行为。
- `AnalysisV2Chart` 的 `navigable` 属性及 `navigate` 事件不影响查询请求、图表数据协议和普通分析编辑模式；视图跳转不附加任意原始 SQL、行条件或用户指定 URL。标题旁备用跳转按钮保留。
- 更新针对性 Chromium 模拟 API 脚本 `finebi_published_views_ui_smoke.cjs`，增加数据点点击跳转与切回原视图的场景。脚本提交不表示已在浏览器实测，发布效果仍以执行结果为准。
- 本批不包含联动筛选、点击传参下钻、跨主题导航或权限治理；P0-05 真实 StarRocks/生产 H2 备份回滚未完成，不发布生产。

## P1-03 第三批：已发布同数据集图表联动过滤（2026-10-14）

- V2 图表点击数据点/明细表行时，发出第一维度的原始结构化字段 ID + 值；页面仅向同数据集且显式使用该维度的其他 V2 组件追加 eq 明细过滤，保留该组件原有筛选规则，并重新调用既有参数化分析 API。
- 发布查看页面展示当前筛选值和清除按钮；发生已配置的跨视图跳转时按跳转优先，不跨数据集强制联动。无本地持久化、无新增后端 SQL 入口或数据库表；过滤逻辑依靠已有后端受控字段 ID 与 PreparedStatement。
- 本批只实现**单字段、同数据集、已发布页**联动。多数据集字段血缘映射、多字段下钻、日期粒度关联、跨主题筛选、性能与真实 JDBC 对账仍未完成；本次提交不能代表 P1-03 或总体计划验收。
- CI 前端类型检查以相应 GitHub Actions 最终结果为准，浏览器真实交互尚未验收，生产未部署。

## P1-03 第四批：联动过滤浏览器验收用例补齐（2026-10-14）

- 将联动验收场景补入已有 `scripts/finebi_published_views_ui_smoke.cjs`：发布页切回总览后点击 V2 图表数据点，检查后续分析查询携带结构化 `fieldId=city, op=eq` 筛选，并检查“清除筛选”入口恢复。
- 此脚本基于 Chromium 和模拟 REST，**代码提交及 CI 语法检查并不等于实际浏览器运行成功**；仅当使用隔离预览服务执行该脚本并记录 PASS 后才能勾选浏览器交互门禁。由于点击图表采用测试画布坐标，真浏览器场景仍可能需要稳定的坐标/事件选择方式。
- 仅补验收脚本，未改查询/数据库服务；StarRocks 真数与生产发布门禁依旧未满足。

## P1-03 第五批：真实 Chromium 发布页验收（2026-10-14）

- 新增独立 GitHub Actions `.github/workflows/p1-published-browser.yml`，仅在发布页、V2 图表、视图布局或对应浏览器测试脚本发生改动时触发；隔离安装 Chromium、构建前端、启动本地 Vite，运行 `finebi_published_views_ui_smoke.cjs`，不依赖生产实例、不触碰数据库。
- 首次真实浏览器运行 [38504839796](https://github.com/NJgaoyang/insight-bi/actions/runs/38504839796) **失败**：SPA 对同一 hash 的 `goto` 没有重新拉取 fixture，导致联动用例等待不到图表。修复为 `page.reload()`，没有通过跳过用例掩盖失败。
- 修复后 [38505038412](https://github.com/NJgaoyang/insight-bi/actions/runs/38505038412) **SUCCESS**：前端 build、隔离 Chromium、发布页导航和联动 eq 筛选、清除、旧仪表板只读路由全部通过。本条仅宣称**模拟 REST 的真浏览器操作**，不代表真实 StarRocks 端到端或生产发布验收完成。
- 对应测试修复 commit `05dcb7b`；相关 workflow commit `88ea4d7`。P0-05 StarRocks SQL 数据对账、生产 H2 元数据备份恢复、P2/P3 仍未完成。

## P2-01 第一批：汇总结果 RANK 排名（2026-10-14）

- `AnalysisQueryPlanner` 新增可选结构化 `rank:{ref,id?,direction?}`，其中 ref 必须为当前请求的数值指标别名。先执行既有明细筛选、聚合及 HAVING，再通过外层 `RANK() OVER (ORDER BY ...)` 对最终维度汇总结果计算排名，相同值并列，不在浏览器端根据可见前 N 行计算。
- 排名 ID、方向及配置字段均校验白名单，引用其他数据集或自定义 SQL 被拒绝；默认向下排序，结果以 `INTEGER` 排名列加入输出合同。旧请求不提供 rank 时 SQL 结构不变。
- 前端分析主题增加“汇总排名”指标选择和升降序方向，排名随保存的 V2 配置在预览和发布页面查询。H2 定向测试验证 150/90/90 数值排名为 1/2/2、实际 SQL、排序、错误引用和冲突 ID 拒绝。
- 相关后端定向 GitHub CI [38505292004](https://github.com/NJgaoyang/insight-bi/actions/runs/38505292004) 已通过。前端构建和浏览器验证以各自最新工作流最终结果为准；未进行真实 StarRocks 对账、生产部署或全量回归。
- P2-01 其余占比、累计、同比环比、多维表格和导出仍未完成；不因此将整阶段标记完成。

## P2-01 第二批：聚合指标占比（2026-10-14）

- 查询 V2 支持可选 `share:{ref,id?}`，必须引用本次选择的数值指标；服务端先按已有 WHERE/GROUP/HAVING 得到全部结果组，再通过外层 `metric / NULLIF(SUM(metric) OVER (),0)` 算出指标占总量的比例，最后才执行排序和 LIMIT。总值为 0 时返回 NULL，避免除零异常。
- 分析主题数据设置增加“指标占总量比例”选择，沿用保存的 V2 JSON 和已发布查询协议。未配置占比的旧查询不变；自定义输出列 ID 及非法引用均由后端拒绝。
- H2 SQL 定向执行用例验证 150/50 分组下份额为 0.75、LIMIT 前计算完整总量、0 分母和无效引用。相关测试 CI [38505836389](https://github.com/NJgaoyang/insight-bi/actions/runs/38505836389) 成功；前端构建和浏览器工作流以各自最终结果为准。
- 未验收真实 StarRocks JDBC、正式生产部署与元数据备份/恢复。P2-01 累计、同比环比、复杂表格及导出未完成。

## P2-01 第三批：单维度累计值（2026-10-14）

- 分析 V2 请求增加可选 `running:{ref,id?,direction?}`，仅允许**恰好一个分组维度**，且 ref 必须为当前请求的数值指标。先计算原本 WHERE/GROUP/HAVING 和可选排名、占比，再经外层窗口 `SUM(metric) OVER (ORDER BY d1 ASC|DESC ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` 得到按维度顺序累计的数值，最后才应用最外层排序与 LIMIT；避免只累计客户端加载的部分结果。
- 前端只在恰好一个维度时显示“按维度累计指标”设置，允许指定指标与升/降序；旧 V2 查询和已有图表继续兼容。
- H2 定向 SQL 执行测试验证三组累计和、末端显示排序不改变窗口累计顺序、LIMIT 前计算完整结果、无效指标及多维度请求拒绝。[38506388127](https://github.com/NJgaoyang/insight-bi/actions/runs/38506388127) 后端定向 CI **PASS**。前端构建、浏览器工作流以相应最新 CI 完结状态为准。
- 仅支持单维度全局累计；分区累计、多维级联、同比环比、高级表格与导出尚未完成。真实 StarRocks、生产备份回滚和部署仍未验收。

## P2-01 第四批：日期维度周期比较基础（2026-10-14）

- V2 查询新增可选 `periodCompare:{ref,mode:'mom'|'yoy',id?}`。仅在选择恰好一个日期维度时允许使用，以汇总结果为输入，对前一可用日期桶进行 `LAG` 比较，输出 `(当前值-基期值)/NULLIF(基期值,0)`；无基期/基期为零返回 NULL。月粒度同比以 12 个可用月桶为步长，年粒度同比以 1 个年桶为步长。
- 日期桶必须连续且完整才能将该版本视为**严格的自然日历同比/环比**；不连续日期和闰年场景尚未实现日期键精确匹配/补桶处理，因此当前是**按已存在分组的序号比较**，不能在缺失月份的业务数据上直接做正式同比对账。
- 分析主题中已增加日期周期比较配置，兼容旧组件；修复嵌套窗口函数的 `ORDER BY` 与最外层排序相混淆的缺陷，避免生成错误 SQL；前端 TypeScript 空值检查已修复。
- 定向测试包含月环比/月同比 SQL 规划、非法维度与无效指标拒绝、最外层排序边界；真实 StarRocks JDBC、浏览器完整联动和生产发布仍未验收。本批 CI 最终结果以最新 commit 对应运行记录为准。
- **P2-01 同比环比仍为有限能力，阶段不标记完成**。严格日历对齐及不连续月份、同比环比的日期匹配、详细图表/导出未完成。

## P2-01 第五批：同比环比按日历键关联（2026-10-14）

- 日期周期比较不再使用 `LAG` 按“上一条有记录的日期”估计自然周期，而使用汇总结果上的 `LEFT JOIN` 精确匹配上一自然日、上一自然月、上一年对应日期或月、年。缺少所需上期桶则返回 `NULL`，不会错用更早可用月份；分母为零也返回 `NULL`。
- 保留单日期维度和数值指标白名单。因两侧均执行受控聚合子查询，编译器按 SQL 占位符出现次序复制过滤参数，以兼容既有条件。新单测验证月份匹配 SQL、双侧参数、缺失日期关联条件及旧配置接口。
- 定向 GitHub Actions [38507343445](https://github.com/NJgaoyang/insight-bi/actions/runs/38507343445) **PASS**。本次覆盖的是 SQL 生成与参数检查；未在真实 StarRocks 上执行该日期方言，也未验证闰年 2 月 29 日的年同比策略、时区边界或生产发布。
- 旧的按位置 LAG 逻辑被替换，本批不代表全部同比环比业务验收完成。保持 P2/P3 和 P0-05 未完成的发布门禁。

## P2-01 第六批：V2 查询结果 CSV 导出（2026-10-14）

- 分析主题和已发布 V2 图表增加“导出 CSV”按钮，直接序列化当前 V2 Query API 实际返回的列、列标签和行，保留后端 SQL 的占比、排名、累计与周期计算结果。无新数据仓库写入操作。
- 以 UTF-8 BOM、RFC 风格双引号/换行转义保证中文与逗号文本兼容；对以 =、+、-、@ 开头（含前导空白）的文本增加引号前单引号，降低电子表格公式注入风险。
- **范围仅是当前请求返回的至多 500 行及其结果列**，不代表全量数据集、Excel XLSX 或服务端导出任务；不会绕过已存在的查询限流或访问权限。
- GitHub 定向前端构建、模拟 REST 的 Chromium 下载验收以当前工作流结果为准；真实 StarRocks、全量导出与生产发布未完成。

## P2-01 第七批：V2 明细表结果搜索和列可见性（2026-10-14）

- 在已有 V2 明细表中增加**仅对当前查询结果**生效的文字搜索、结果列显示/隐藏，保持至少一列可见；原后端数据查询、计算语义和图表不改动。
- CSV 导出按明细表当前搜索结果和可见列输出；其它 V2 图表继续导出当前返回的原始列与行。未实现服务端跨页搜索、全量 Excel、保存列可见性偏好或高级透视表，勿与这些功能混淆。
- 在现有 `finebi_published_views_ui_smoke.cjs` 加入 Chromium 真实浏览器交互（明细搜索、隐藏列），仅定向执行受影响前端及发布页测试。
- 初始交付 `51237c1`，浏览器用例 `f2f3240`，最终验收以最新 CI 工作流结果为准；生产未部署，StarRocks 对账仍未通过。

## P2-01 第八批：高级表格默认列配置持久化（2026-10-15）

- V2 分析主题新增 `tableHiddenColumnIds`，编辑表格组件时可预设要隐藏的维度与指标列，该设置随原组件配置保存、发布快照携带并在只读视图生效。运行时支持临时调整列可见性，并同步当前可见列的 CSV 导出；发布页临时调整不回写。
- 编辑器限制默认隐藏列最多为当前维度及指标总列数减一，运行时也不允许隐藏结果中的最后一列。未配置该字段的旧组件保持全部显示。
- 前轮表格搜索与列可见性 Chromium 验收 [38508564662](https://github.com/NJgaoyang/insight-bi/actions/runs/38508564662) 已通过；本轮新增的持久化配置代码以本轮前端 TypeScript/Vite 定向 CI 最终结论为准，尚未宣称实库或发布版本跨会话持久化浏览器验收通过。
- 本批仍不实现全量导出、服务端跨页过滤或复杂透视表；StarRocks 数据对账、生产备份回滚与 P2/P3 其他功能未完成。

## P2-01 第九批：高级表格发布快照与未保存状态验收（2026-10-15）

- 修复 `AnalysisV2Chart` 直接写入嵌套组件 props 的问题：列显隐操作改由 `tableColumnsChange` 事件交给 `AnalysisSubject`，由编辑器 `mark()` 正确记录撤销历史、未保存状态与保存指纹；发布页只保留当前会话临时显隐。
- 保存和发布的分析快照增加 `tableHiddenColumnIds` 数据边界校验：仅允许本组件已绑定的维度/指标 ID，拒绝重复、未知、超过上限及隐藏全部输出列。旧组件未配置该字段时保持兼容。
- 后端 H2 定向测试验证真实主题保存、发布快照读取和非法配置拒绝：GitHub Actions [38669503341](https://github.com/NJgaoyang/insight-bi/actions/runs/38669503341) **PASS**。
- Chromium 模拟 REST 的真实浏览器用例验证发布页默认隐藏“城市”、临时显示、刷新后恢复已保存隐藏状态，以及 CSV 导出表头仍只包含可见列：[38669546022](https://github.com/NJgaoyang/insight-bi/actions/runs/38669546022) **SUCCESS**，包含前端构建和原有发布页兼容测试。
- 尚未验证真实 StarRocks、生产部署、跨用户权限与生产备份恢复。P2-01 仅完成此批列配置保存和发布展示闭环，不代表整个 P2-01、P2-02 或 P3 完成。

## P2-02 第一批：发布操作审计基础（2026-10-15）

- 新增独立 `insight_publication_audit` 元数据表，存储报表 ID、事件、操作者、版本号与时间，不存业务查询结果、账号密码或数据源凭据。仍沿用现有 H2/MySQL 初始化方案，不改旧主题/数据集结构。
- 发布 `PUBLISH`、撤销发布 `UNPUBLISH`、历史版本恢复 `RESTORE` 在各业务操作成功后写入审计表；发布和恢复与审计写入处于同一个 Spring 事务，撤销发布改用受控事务。对未发布草稿重复撤销不生成假事件。
- 新增受当前单管理员会话和 CSRF 基础机制保护的 `GET /api/dashboards/{id}/publication-audit` 只读接口，仅返回指定已有报表最多 100 条事件元数据；分析主题编辑页加入“发布记录”弹窗。
- H2 定向 [38669871212](https://github.com/NJgaoyang/insight-bi/actions/runs/38669871212) **PASS**，覆盖成功发布、撤销发布、版本恢复、失败恢复无审计记录、无操作不重复及不同资源 ID 隔离；前端构建 [38669943290](https://github.com/NJgaoyang/insight-bi/actions/runs/38669943290) **PASS**，Chromium 模拟 REST 浏览器验收 [38670019914](https://github.com/NJgaoyang/insight-bi/actions/runs/38670019914) **PASS**，覆盖“发布记录”操作与表格展示。
- **这是 P2-02 的首个基础审计切片，不是完整权限系统**。当前登录仍是单管理员，操作者固定记录 `admin`；未实现用户/部门/角色、资源和行列权限、目录共享规则、发布审批工作流、删除审计、日志留存/脱敏策略以及多用户并发治理。未执行真实 MySQL 迁移、StarRocks 对账、生产部署或全量回归。

## P2-02 第二批：安全预配置用户、角色与报表授权（2026-10-15）

- 增加 `insight_governance_user`（账号、显示名、角色、禁用状态）与 `insight_dashboard_grant`（用户 ID、已发布报表 ID、`READ_PUBLISHED`）两张独立的治理元数据表。只提供 `VIEWER` / `EDITOR` 预配置角色，不允许创建同名 `admin`，不接受由客户端指定 `enabled` 字段。
- 管理接口挂在 `/api/governance`，继续受既有 **admin 会话 + CSRF** 拦截器保护。支持预配置账号创建/列出/删除、修改预配置角色、添加/查询/撤销已发布报表只读授权；非已发布报表拒绝预配置，重复授权幂等返回。
- 后端权限判定 `canReadPublished` 采用**拒绝优先**原则：同时要求账号启用、角色在允许范围、存在精确的只读授权和报表当前已发布。H2 单测通过真实发布、授权、撤销发布、撤销授权、账户删除等情景验证，且证明**默认禁用账号即使预配置授权也无法有效访问**。
- “系统管理 → 权限治理”增加管理员预配置界面、未启用提醒和授权窗口，保持原数据连接页面。前端 TypeScript/Vite 和真实 Chromium（模拟 REST）定向 CI 以最终运行记录为准。仅在原受影响范围内构建、测试。
- **安全边界：本批没有开放普通用户登录或创建密码，也没有把 `canReadPublished` 接到所有数据集、V1/V2 查询及历史版本接口。** 因此后台的授权是未来接入时的预配置，不宣称读者已经可以登录、安全查看报表，不允许在生产中当作多用户权限平台使用。部门组织结构、发布审批、行列级权限、目录共享、完整审计、StarRocks 真库验证和 P3 可靠性仍未完成。

## P2-02 第三批：部门组织树和禁用账号归属（2026-10-15）

- 新增独立 `insight_governance_department`（UUID、部门名称、父部门、创建时间），通过可重复执行的数据库初始化给旧 `insight_governance_user` 增加可空 `department_id` 字段；原账号仍禁用，无密码/登录入口改动。
- 管理员专属 API `GET/POST /api/governance/departments`、`PUT/DELETE /api/governance/departments/{id}`，以及 `PUT /api/governance/users/{id}/department`，允许创建、重命名、迁移层级、删除空部门和用户调入/移出部门。约束总数 ≤500、树深 ≤10、同级部门名称不重复、父部门必须存在、不允许自环/移动到子部门、含用户或子部门不可删除。全部操作仍需既有 admin 会话及 CSRF 校验。
- 「系统管理 → 权限治理」已增加紧凑型部门树、创建/编辑、层级调整与账号归属选择；普通账号仍默认 `enabled=false`。**部门归属不等于授权，不能通过切换部门继承报表访问、数据集访问或启用登录。**
- 定向 H2 测试覆盖树结构、重复名称、孤儿父节点、祖孙环、删除保护、归属与解除归属、重新执行元数据初始化；后端 CI [38671023626](https://github.com/NJgaoyang/insight-bi/actions/runs/38671023626) **PASS**。前端 TypeScript/Vite 构建及真实 Chromium 模拟 REST 交互 [38671156424](https://github.com/NJgaoyang/insight-bi/actions/runs/38671156424) **SUCCESS**。
- 未完成普通用户登录、部门级继承、所有 V1/V2 查询/数据集接口的资源权限校验、行列权限、共享目录审批及生产 MySQL 数据迁移验证；本阶段不可作为完整多用户权限系统投产。

## P2-02 第四批：独立普通用户登录与已发布 V2 报表受控查询（2026-10-15）

- 单管理员架构保留；普通用户只通过新增的 `/api/reader/login` 建立独立的 reader 会话，`/api/reader/me`、`/api/reader/logout`、授权目录 `/api/reader/catalog` 及 `/api/reader/dashboards/{id}` 访问其**精确授权且当前已发布**的分析主题；普通用户会话不能使用 `/api/datasets`、`/api/query`、`/api/analysis/v2/query`、`/api/governance` 或管理员 CRUD 接口。
- 新增可空的 `insight_governance_user.password_hash`，旧账号默认禁用。管理员必须为用户设置至少 12 字符的密码（随机盐 PBKDF2-HMAC-SHA256，210000 次迭代、常数时间比较），再显式启用。设置/重置密码会禁用该账号，启用条件必须包含密码和受控角色；账号禁用、密码更换与角色失效均在请求入口重新校验，已有 reader 会话随之拒绝。登录设 15 分钟失败次数限制，成功时销毁旧会话并生成新 CSRF 令牌，不泄露密码哈希至管理界面。
- **只读数据查询来自服务端已发布快照**：`POST /api/reader/dashboards/{id}/widgets/{widgetId}/query` 不接受用户传入数据集、SQL、维度、指标、筛选、加工版本或 LIMIT，所有这些参数均由已授权报表的原始已发布 V2 组件装配，并使用已有受控 SQL 规划器、只读连接和并发上限；输出行上限额外限制在 200。未授权报表、失效发布、非法组件或自定义参数一律拒绝。普通用户直接打开 V1 或混合旧版组件的报表暂不支持。
- 前端沿用登录屏幕，普通用户进入独立「授权报表」只读目录；`PublishedReport` 只在 reader 模式访问专属报表接口，`AnalysisV2Chart` 使用独立的、无客户端查询配置的只读 V2 请求，普通用户不能打开主工作台、数据源/数据集或编辑页；本轮 reader 模式暂未开启联动筛选与 V1 图表。
- 定向 H2 `ReaderApiTest` 覆盖：初始禁用、强度校验、密码哈希、启用顺序、普通用户不能继承管理员权限、CSRF、禁用/重置后的旧会话即时失效、未授权报表拒绝、组件快照绑定与客户端伪造参数拒绝、报表取消发布后失效。对应后端 CI [38671828803](https://github.com/NJgaoyang/insight-bi/actions/runs/38671828803) **PASS**。Vue/TypeScript 构建与 Chromium 模拟 REST 的真实页面流（管理员设置读者密码→启用→读者登录→已授权目录→V2 图表只读查询）[38672208814](https://github.com/NJgaoyang/insight-bi/actions/runs/38672208814) **SUCCESS**。
- 会话吊销加固：新增 `auth_version` 元数据，管理员禁用、重新启用账号或设置/重置密码时递增版本号；reader 会话登录时锁定当前版本，所有普通用户请求均验证版本，避免“先禁用再重新启用”使旧会话意外恢复；同时拒绝缺少 CSRF 会话令牌的写请求。对应 H2 安全定向 [38672493358](https://github.com/NJgaoyang/insight-bi/actions/runs/38672493358) **PASS**。浏览器页面刷新验收以追加用例的最新 CI 运行结论为准。
- **尚未具备生产级完整权限隔离**：此批只允许从已发布 V2 分析主题读取该组件完整预配置结果。未实现普通用户对 V1 报表/复杂加工/动态下钻的完整权限语义、行列级权限、组织继承、公共目录 ACL、多用户负载及真实 MySQL/StarRocks 对账；部署前必须确认 HTTPS、反向代理、会话 Cookie 和数据库元数据一致性备份。保持开发分支状态，不直接生产发布。

## P2-02 第五批：普通用户数据集行级精确匹配限制（2026-10-15）

- 完成一条明确的**用户 × 数据集** 行级过滤闭环，新增 `insight_reader_row_policy`（用户、数据集、原始 STRING 字段 ID、允许值）元数据表，初版每个用户+数据集最多一条、仅支持字符串精确等值限制；不存在规则时维持既有报表授权可见范围，**不代表默认拒绝所有数据行**。
- 管理员 `GET /api/governance/users/{id}/row-policies`、`PUT/DELETE /api/governance/users/{id}/row-policies/{datasetId}` 可以查看、设置/替换与删除限制。字段必须来自该数据集已登记的原始 STRING 列，拒绝陌生/数值字段、额外 JSON 参数、缺失资源、空值及超长值。用户被删除时清理规则。后台“系统管理 → 权限治理 → 数据行限制”允许选择数据集、文本字段与允许值，并明确提示移除限制会扩大用户的数据可见范围。
- 普通用户 V2 查询始终先校验报表授权，从**已发布快照**装配查询，再由服务端查询行规则并将其追加到 `filters.pre`，进入已有白名单 `AnalysisQueryPlanner` 编译、SQL 参数化绑定。过滤发生在 `GROUP BY` 及排名、占比、累计值之前，浏览器仍不能传入任意筛选条件、数据集或 SQL。保存配置超过规划器 20 条过滤上限时拒绝查询；规则引用的源字段被删除/更名失效时不能静默放行。
- 定向 H2 `ReaderApiTest` 创建真实 `sales` 数据，确认城市“北京”聚合仅为 150 而非全城市合计 350，并验证恶意字符串只是 JDBC 绑定参数、非法数值字段拒绝、客户端不能移除规则、替换/删除以及账号删除后规则清理。后端定向 CI [38673117172](https://github.com/NJgaoyang/insight-bi/actions/runs/38673117172) **SUCCESS**。前端/Chromium 新用例仅模拟 HTTP，最终状态以最新 CI 为准，不等于真实 StarRocks 验收。
- **边界**：不支持多值 `IN`、多字段组合、动态组织继承、原始数据集直接访问、列级脱敏或历史 V1 组件；未验证 StarRocks JDBC、原地 MySQL 迁移及生产部署。此版本行规则是管理员可选的附加收窄，不替代完整企业级行列权限模型。

## P2-02 第六批：多值行级授权（2026-10-15）

- 在上一批用户+数据集原始文本字段单值限制上，新增 **1 至 20 个互不重复的允许值**，例如为同一用户授权北京、上海两地。客户端只可通过管理员接口维护配置；普通用户仍无权自选数据集和修改强制过滤。
- 新表 `insight_reader_row_policy_value` 与旧 `insight_reader_row_policy` 并存，旧单值记录无需迁移即可生效；新增多值配置在一个事务内替换值集合并保留首个值作为旧结构兼容字段。行级规则查询返回 `values`，仅管理员可编辑。非法字段、重复值、空数组、超过20个值、单值和多值同时提交均拒绝。
- 服务端在从已发布报表快照装配的查询上，自动把单值编译为 `eq`，多值编译为 `in`，复用现有白名单 SQL 规划器的 JDBC 占位符，在汇总、占比、排名、累计计算之前实施过滤。数据库值从不拼接成原始 SQL。删除用户同时清理多值子表。
- 「系统管理 → 权限治理 → 数据行限制」输入区支持逐行设置多个城市，表格回显多值列表；单值仍沿用旧 API，兼容既有浏览器流程。
- 定向 H2 [38674817103](https://github.com/NJgaoyang/insight-bi/actions/runs/38674817103) **SUCCESS**：执行真实 SQL 验证北京150 + 上海200 = 350，验证 IN 参数化、重复/空/超限/双格式拒绝、非法替换不改变旧规则以及旧单值记录兼容。
- **未完成项**：新增多值输入路径的独立 Chromium 实际交互尚未完成确认；真实 StarRocks JDBC、MySQL 原地迁移及生产部署未验证。仍不支持多字段 AND/OR、多用户继承、行列脱敏及完整目录 ACL。

## P2-02 第七批：部门报表授权继承与读者目录一致性（2026-10-15）

- 新增 `insight_department_dashboard_grant`，允许管理员按部门授予当前**已发布分析主题**的 `READ_PUBLISHED` 权限；部门自身和所有下级部门的有效 VIEWER/EDITOR 成员可继承该报表**只读**授权。不开放报表编辑、数据集管理、SQL/元数据 API；仍保留已有个人报表授权，二者取并集，不进行普通用户角色升级。
- 管理员专属接口：`GET/POST /api/governance/departments/{id}/grants` 及 `DELETE /api/governance/departments/{id}/grants/{dashboardId}`。不存在部门、非分析主题、未发布报表、额外权限字段均拒绝；重复授权幂等。删除无人/无子部门的部门时一并清理其直接报表授权。
- `GovernanceApi.effectiveDashboardIds` 基于当前账号启用状态和角色、现有用户直接授权以及当前组织树从用户部门至根节点逐级合并已授予的报表 ID。每次请求现查，不缓存：用户调出部门、父部门迁移、直接/继承授权撤销立即重新判定；树循环、孤儿父部门或超过深度上限时直接拒绝。读取目录 `/api/reader/catalog` 与报表查看、V2 组件只读查询共用此策略，并只返回已发布分析主题，避免目录和接口判权不一致。
- 「系统管理 → 权限治理 → 部门管理」增加部门报表授权弹窗，支持查看、授权和撤销，只展示已发布分析主题并清楚标明下级部门继承、个人授权独立、行级数据限制依旧生效。
- H2 定向 `GovernanceApiTest`、`ReaderApiTest` 覆盖三层继承、账号禁用、迁出/调回、迁移祖先、撤销、未发布报告、直接授权独立和删除部门清理；后端定向工作流 [38675258178](https://github.com/NJgaoyang/insight-bi/actions/runs/38675258178) 及 [38675230566](https://github.com/NJgaoyang/insight-bi/actions/runs/38675230566) **SUCCESS**。新增 Chromium 部门授权弹窗回归用例的最终结论另以该次工作流结果为准，不冒充生产浏览器和真实数据库验收。
- 边界：不实现部门行级权限继承、资源目录 ACL、审批流程、V1 旧组件、安全跨租户查询、MySQL/StarRocks 真库验证或生产部署。管理员**原有个人行级过滤**在继承报表后继续由服务器强制执行。该切片并非完整企业级权限系统交付。

## P2-02 第八批：普通用户列级拒绝与敏感字段保护（2026-10-15）

- 新增 `insight_reader_column_deny` 元数据表：按用户+数据集登记最多 20 个**禁止查看的原始字段**，原用户及行级授权保持不变。管理员可通过 `GET /api/governance/users/{id}/denied-columns` 查看、`PUT /api/governance/users/{id}/denied-columns/{datasetId}` 原子替换、`DELETE` 清理；严格校验数据集存在、字段真实属于该数据集、字段无重复及请求无多余参数。删除账号时连同字段禁止规则一起清理。
- 安全策略为**拒绝含受限字段的整份已发布分析主题**，而非仅前端隐藏数据列。新 `ReaderColumnGuard` 在普通用户的 `/api/reader/catalog`、`/api/reader/dashboards/{id}` 与 `/api/reader/dashboards/{id}/widgets/{widgetId}/query` 执行同一策略，避免目录展示与查询权限不一致；报表聚合、筛选、组件引用任一受限字段即拒绝返回。因为尚未形成可审计的计算字段依赖图，**对存在字段限制的数据集，任何计算字段引用或启用数据加工链的报表均先拒绝**；限制解除后可恢复访问，无需重新发布。这是有意偏保守的安全策略。
- 管理员“系统管理 → 权限治理 → 敏感列限制”页面支持选择用户、数据集、多个字段，保存后即生效；清空已有规则需二次确认，因为会扩大数据可见范围。普通用户仍只能访问已授权的、符合列级规则的已发布 V2 组件；原有部门报表继承和个人行级过滤均保留。
- H2 定向 `ReaderApiTest` 覆盖：收入可看而薪资聚合拒绝、通过计算字段/加工链间接读取拒绝、目录与查询拒绝一致、无效/重复/越权字段不能保存、清空规则立即恢复、删除用户清理规则。[38675814074](https://github.com/NJgaoyang/insight-bi/actions/runs/38675814074) **SUCCESS**。Vue/TypeScript 前端受影响构建 [38675870187](https://github.com/NJgaoyang/insight-bi/actions/runs/38675870187) **SUCCESS**。
- **验收边界**：本轮未完成新增配置弹窗的独立 Chromium 验收；真实 MySQL 原地迁移与 StarRocks 的数据核算仍未验证，未部署生产。当前只防止通过受控 V2 报表组件引用禁止的元数据字段，不具备原始 SQL 语义血缘识别，不能承诺能阻止管理员数据集 SQL 提前生成的派生敏感字段泄露。资源目录 ACL、字段掩码、跨部门继承策略和完整审计仍未完成，不可宣称列级安全治理全量完成。

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
