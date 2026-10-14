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
