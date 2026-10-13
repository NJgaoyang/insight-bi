# FineBI 自助分析计算引擎 P0 — 开发记录与交付说明

更新：2026-10-13；分支：`feat/finebi-analysis-engine-p0`。**本文件记录真实实现进度，不代表整个 P0 已验收完成。**

## 总体状态

| 任务 | 当前状态 | 已提交代码 | 未完成的验收 |
|---|---|---|---|
| P0-00 | **部分完成** | GitHub 分支建立，旧代码/API 检查完成 | 由于实行“GitHub 开发、自行拉取部署”，生产 MySQL/JAR 备份和独立预览环境由部署者操作；本次未操作远程服务器 |
| P0-01 | **核心代码完成，迁移待线上验证** | `FieldCatalog`、MySQL `insight_dataset_field` 和 `insight_calculated_field` 两张增量表、`GET /api/datasets/{id}/fields` | 真实 StarRocks JDBC 类型元数据与旧数据持久化验收、字段重命名引用迁移 |
| P0-02 | **核心代码完成，AST 只读校验已加入，生产安全尚需审计** | `AnalysisQueryPlanner`、`POST /api/analysis/v2/compile`、`.../query`；最多 3 维度/5 指标；分组、日期粒度、排序、结果上限 | JSqlParser SELECT 校验外已增加首批 SQL 函数白名单，未知调用拒绝；仍需 StarRocks 真实兼容性、只读权限、查询取消与并发限制验收 |
| P0-03 | **基础公式与前后过滤完成，高级语义待完善** | `FormulaCompiler`、行级/聚合阶段、WHERE/HAVING、参数绑定、公式新增/修改/删除/验证 | 已补公式更新的自引用、循环及依赖阶段检查；仍缺完整类型推断、复杂表达式依赖管理和 CASE/日期/窗口能力 |
| P0-04 | **基础 UI 已接入，模拟 REST 浏览器联调已通过** | 新组件 V2 字段列表/多槽拖拽、计算字段输入、过滤、排序、图表渲染与保存；原 V1 组件保留 | 多字段与公式 UI 的模拟 API 浏览器测试已通过；仍缺真实 StarRocks E2E、完整按指标样式及其他图表边界验收 |
| P0-05 | **未完成** | 已有 H2 数据执行验证与 GitHub CI | 非生产 StarRocks 真数对账、部署前数据库备份、生产/预览启动后 UI 检查、回滚演练 |

## 已实现接口（均经过原有会话与 CSRF 拦截器）

- `GET /api/datasets/{id}/fields`：含物理和计算字段、稳定 ID、逻辑类型和阶段。
- `POST /api/analysis/v2/compile`：验证结构化请求，返回 SQL 预览/字段及参数个数，不执行查询。
- `POST /api/analysis/v2/query`：只通过受控字段生成分组 SQL，参数使用 `PreparedStatement`，StarRocks JDBC 执行并返回数据。
- `GET|POST /api/datasets/{id}/calculated-fields`、`POST /api/datasets/{id}/calculated-fields/validate`、`PUT|DELETE /api/datasets/{id}/calculated-fields/{formulaId}`。

原 `POST /api/query`、旧图表配置、旧分析主题访问方式未删除。

## 关键计算规则与验证

1. `[revenue]-[cost]` 是行级字段，允许再 SUM。
2. `SUM([profit]) / NULLIF(SUM([revenue]),0)` 为聚合公式，利润率应按当前维度重算，零收入时结果为 NULL。
3. `filters.pre` 生成 `WHERE`，`filters.post` 生成 `HAVING`；不允许在明细过滤直接引用聚合字段。
4. 字段标识符仅由服务器元数据映射，排序只允许本次维度/指标引用，过滤值参数化；不接受前端自由 SQL 字符串。
5. 单个图表最多 3 个维度、5 个指标，查询默认 200 行、最高 500 行。
6. V2 数据集来源 SQL 先通过 JSqlParser AST SELECT 根节点验证；`SELECT ... FOR UPDATE` 与多语句拒绝，底层仍需要 StarRocks 只读账号。
7. GitHub Actions 持续运行 `BiQueryInTest`、`FineBiWorkspaceFolderTest`、`AnalysisQueryPlannerTest`、`AnalysisEngineSqlExecutionTest` 及 Vue 的 TypeScript + Vite 构建。
7. 仓库 `frontend/package-lock.json` 已从仅在腾讯云可访问的镜像改为公开 npm 下载地址，依赖版本和完整性校验值未变。

**限制**：数据集来源 SQL 当前沿用 `DatabaseStore.safeSql` 的字符串限制，还不是完整 SQL AST 语法校验；平台管理员自定义 SQL 仍必须使用只读数据库账号。**未通过真实 StarRocks 环境测试前不要把这一版标为生产完成版。**

## 开发与部署约定

- 代码只提交到 GitHub 分支，不在开发机覆盖 `/opt/insight-bi`。
- **部署前请先独立备份 MySQL `insight_` 元数据表以及当前应用 JAR**。启动新版本时 `DatabaseStore.schema()` 会自动执行两条 `CREATE TABLE IF NOT EXISTS`。不执行数据删除或现有表字段重写，但首次启动会建新表。
- 从 GitHub 获取后运行：

```bash
git fetch origin
git switch feat/finebi-analysis-engine-p0
cd backend && mvn -q -Dtest=BiQueryInTest,FineBiWorkspaceFolderTest,AnalysisQueryPlannerTest,AnalysisEngineSqlExecutionTest test
cd ../frontend && npm ci && npm run build
```

- 真实 StarRocks 对账只能用非生产测试库/已允许的测试表。核对多维分组、行级/聚合公式、WHERE/HAVING、NULL、零除、Decimal 精度、权限及慢查询。
- 未通过 P0-05 之前，建议在独立测试端口验证，不要直接替换在用的 `18110`。
- 前后端 CI 报告在 [GitHub Actions](https://github.com/NJgaoyang/insight-bi/actions)；阶段待办详见 [P0 任务书](FINEBI_ANALYSIS_ENGINE_P0_TASKS.md)。


## 2026-10-13 续开发：计算字段依赖保护与 SQL 安全边界

目标分支：`feat/finebi-analysis-engine-p0`（未触碰 `main` 或生产 JAR）。

- 修复 `AnalysisApi.updateFormula`：先使用**拟更新公式**构建计算字段依赖图，阻止自引用/间接循环及修改导致依赖字段计算阶段或返回类型不一致；发生验证错误时不写数据库。
- 已发布 `published_json`、`insight_dashboard_version.content_json` 引用某公式时，禁止原地修改或删除公式，避免历史版本结果语义悄然变化；可新建计算字段并在草稿里切换。
- 加强 `ReadOnlySqlGuard`：JSqlParser 先解析为 SELECT，再对 SQL 数据集中的函数调用使用首批保守白名单；未知函数/UDF（例如 `GET_LOCK`）拒绝，保持字符串字面量不受误判。StarRocks 方言函数扩展须先审核再增列。
- 新增 `AnalysisFormulaDependencyTest`、`ReadOnlySqlGuardTest`，并加入 GitHub Actions 的定向 Maven 测试。
- 提交：`4721a08`（依赖和快照保护）、`3c7b963`（依赖回归测试）、`fc90416`（函数白名单）、`0280e14`（SQL 守卫测试）、`22bd28b`（CI 测试清单）。
- **验证状态**：GitHub Actions 工作流 #32 与 #34 均显示 backend、frontend 成功（#34 见 https://github.com/NJgaoyang/insight-bi/actions/runs/38367420381）；包含两项新增 Java 测试。开发机仍离线，尚无真实浏览器、MySQL 备份与 StarRocks 真数对账，故不能标注 P0-05 通过。
- V2 分析图表另修复过期请求未取消、NULL/无效数值被强制转为零的问题；提交 `f75ad68`，由工作流 #34 验证前端构建通过。该 CI 不代表真实浏览器拖放或 StarRocks 验收。
- **发布门禁**：G0 与 P0-05 均未通过；保留原 `18110` 运行环境；不得据此直接发布。需独立备份 `insight_` 元数据、旧 JAR，验证自动建表及 SQL 白名单对现有数据集的兼容性。


## 2026-10-13 开发机复连后的 P0-04 增量与真实执行证据

**代码分支：** `feat/finebi-analysis-engine-p0`。GitHub 是当前最新版本来源；开发机本地 Git 分支起始仍在 `36732c4`，服务器直连 GitHub HTTPS 超时，未强行重置或覆盖。使用本地共享克隆 `/opt/insight-bi-p0-preview` 和从 GitHub 获取的前端文件进行隔离验证，绝不在生产 `18110` 上开发热替换。

- 分析主题本地编辑历史：撤销、重做，包含字段增删、组件创建/删除、字段绑定变更；保存成功后重置历史。维度与指标可通过箭头或 HTML5 Drag & Drop 调整槽位排序。
- `scripts/finebi_multifield_ui_smoke.cjs`：Chromium 真浏览器执行，选择 2 个维度和 2 个指标，拖动换序、撤销、重做、保存并刷新重进；**PASS / 退出码 0**。模拟登录和 V2 查询 API，不接真实 StarRocks。
- `scripts/finebi_formula_ui_smoke.cjs`：错误公式返回 400 后对话框仍保留，正确公式保存成功、字段重新发现、绑定到指标并刷新恢复；**PASS / 退出码 0**。模拟 REST 响应，不代表真实公式数据对账。
- 开发机隔离前端 `npm ci --offline --no-audit --no-fund && npm run build` **PASS / 退出码 0**（Vue TypeScript 与 Vite）；旧本地分支针对 `BiQueryInTest,AnalysisQueryPlannerTest,AnalysisEngineSqlExecutionTest` 的 `mvn -q -o -Dtest=... test` **PASS / 退出码 0**。本地旧分支测试不等于新提交后端测试，后者由 GitHub Actions 验证。
- GitHub Actions #42 和 #50 后端、前端均为成功；浏览器脚本另外纳入 Node 语法检查。浏览器本身是在开发机执行，而非 GitHub Actions。
- 相关提交：`730c101`（撤销/重做）、`012cc5a`（字段槽拖动换序）、`cf98c35` 与 `3f8d98f`（多字段浏览器脚本）、`2489d0f` 与 `a6ecd64`（公式浏览器脚本）、`02c88eb`（CI 语法检查）。

### P0-00 / P0-05 仍未通过：实际运行状态与恢复约束

- 开发机 `VM-0-13-tencentos` 已连通，生产 `insight-bi.service` 在 `18110` 运行。现网 systemd 实际配置的数据源为 **H2 文件 `/var/lib/insight-bi/metadata`**，不是 README 假定的 MySQL；禁止未核实直接执行 MySQL 迁移。
- 检查发现原目录 `runtime/` 缺失、线上所依赖的原始运行 JAR 被删除。已通过仍在运行的 JVM 文件描述符恢复原始约 37 MB 的 JAR，置于 `runtime/recovery-20261013-162336/`，同时恢复到 `backend/target/insight-bi-0.1.0.jar` 的原启动路径。备份 systemd 服务配置，以上私有备份文件权限 600，`runtime/` 受 Git 忽略。
- 复制了一份正在使用的 H2 `metadata.mv.db` 到同一恢复目录，但**在线文件复制无法证明事务一致性**，不视为通过 G0 的权威备份；正式升级前应通过受支持的方式完成一致性备份并验证恢复。
- 当前 `runtime/local.env` 原配置文件仍缺失，systemd 服务依赖该文件；**未经恢复与检查，不允许停止或重启现有服务**。不把敏感凭据放入仓库、日志或此交接文档。
- 最新 GitHub 代码尚未同步到服务器工作分支，也没有发布到生产。后续恢复网络后先检查工作树、拉取分支、做一致性备份、在独立端口验证并跑 StarRocks 非生产对账，完成 G0～G4 与 P0-05 后方可部署。
- 为防止当前缺失 `runtime/local.env` 时一键部署停止现有 Java 进程，已修改 `scripts/deploy.sh`：停止服务前验证环境文件、旧 JAR 及服务状态，备份原 JAR，启动/健康检查失败时恢复原 JAR。**没有执行部署脚本**；仅在隔离预览副本上执行 `bash -n` 语法检查 PASS。提交 `3c3a048`、CI 脚本检查 `68342c9`。
- 所有上述浏览器用例完成后已停止隔离预览 Vite（`18117`），线上 `18110/api/health` 返回 `{"ok":true}`，生产服务保持 active。


## 2026-10-13 组件指标级样式增量与回归

- V2 `MeasureV2` 新增可选配置：`seriesType`、`axis`、`decimals`、`color`、`showLabel`；旧 `Widget`、无上述属性的 V2 JSON 继续可读。新属性不参与后端聚合 SQL 语义。
- `AnalysisSubject.vue` 每个指标独立配置别名、小数位、颜色、左右轴、组合图系列类型、数值标签。只在相关图表类型展示会生效的控制项。
- `AnalysisV2Chart.vue` 在 ECharts 系列、双轴、tooltip、数据标签及表格/指标卡数值格式上实际应用设置；样式变更只调用图表重渲染，不重新调用 V2 查询接口。空值仍保持 NULL，而不是变成零。
- 去除首屏对不可访问的 Google Fonts CSS 依赖，避免隔离环境浏览器 `load` 等待超时。
- 隔离开发机离线 npm/TypeScript/Vite 构建 **PASS**；`finebi_multifield_ui_smoke.cjs` 已验证两个指标独立设置持久化、数值轴、三位小数、数据标签与样式修改不重查数据 **PASS**；`finebi_formula_ui_smoke.cjs` 再次 **PASS**。这些浏览器测试依旧使用模拟 API。
- 涉及提交：`715b3bf`、`e6322fb`、`acc3081`、`7e7951f`、`6f05f2e`、`a878024`、`40d2765`、`2b8884e`、`65eb6c8`。尚待 StarRocks 图表实际数值对账、浏览器多组合类型边界和真数权限检查，G4/P0-05 继续未通过。

## 2026-10-13 最新隔离后端定向回归

- 从 GitHub P0 分支将最新 `AnalysisApi.java`、`ReadOnlySqlGuard.java` 及 `AnalysisFormulaDependencyTest.java`、`ReadOnlySqlGuardTest.java` 同步到 **独立预览克隆** `/opt/insight-bi-p0-preview`，未修改生产源码工作树。
- 执行 `(cd /opt/insight-bi-p0-preview/backend && mvn -q -o -Dtest=BiQueryInTest,AnalysisQueryPlannerTest,AnalysisEngineSqlExecutionTest,AnalysisFormulaDependencyTest,ReadOnlySqlGuardTest test)`，**退出码 0，PASS**。仅出现 Mockito 动态代理警告，不影响测试结果。
- GitHub Actions 工作流 #86 `backend`、`frontend` 均为 **success**，包括前端构建、后端定向单元测试与脚本语法检查。
- 至此本轮代码级回归与隔离的模拟浏览器交互均成功；**仍未执行真实非生产 StarRocks 查询对账及在线元数据一致性备份**，不要将 G0/P0-05 勾选为完成。

## 2026-10-13 续开发：查询边界、字段刷新和非生产对账入口

本次增量均提交至 `feat/finebi-analysis-engine-p0`，仅在 GitHub 分支与服务器独立克隆上实施，生产服务未重启。

1. **准确分组与截断**：维度-only 的 V2 查询同样执行 `GROUP BY`，拒绝同一字段重复绑定维度；V2 SQL 查询使用额外一条 sentinel 记录判断是否真的被截断，恰好 500 行返回 `truncated=false`，501 行返回前 500 条、`truncated=true`；V1 `readRows` 的 500 行硬上限和响应形状保持不变。
2. **字段类型刷新**：物理列名不变、SELECT 返回 JDBC 类型变化时，更新 `insight_dataset_field.logical_type` 与默认 `role`，保留 `field_id` 与用户自定义显示名称。此阶段尚未实现物理列重命名识别，不能把 rename 视为受支持迁移。
3. **去重计数**：结构化 `measures[].distinct=true` 只允许用于 `COUNT(字段)`，编译 `COUNT(DISTINCT biq.字段)`；非法 SUM DISTINCT、COUNT(*) DISTINCT 被拒绝，字段引用仍仅允许后台元数据 ID。前端支持将非数值维度作为 COUNT 指标，并提供去重开关。MIN/MAX 列类型按源字段返回。
4. **隔离定向验证**：开发机执行六组原有/新增 Maven 查询回归，再执行包含 `FieldCatalogRefreshTest` 的七组定向测试，均 `exit=0`；Vue TypeScript/Vite 构建 `exit=0`；Chromium `finebi_multifield_ui_smoke.cjs` 和 `finebi_formula_ui_smoke.cjs` 均 `exit=0`，包括文本字段 `COUNT DISTINCT` 的保存、刷新、重新查询。浏览器 API 为模拟数据。
5. **真实 StarRocks 对账入口**：新增 `StarRocksReadOnlyReconciliationTest`，以 SELECT UNION ALL 内联构造北京/上海/南京四行固定数据，测试多维/月份、多指标、聚合利润率、除零 NULL、WHERE 与 HAVING、COUNT DISTINCT；不创建/写入任何业务表。默认 `@EnabledIfEnvironmentVariable` 跳过，仅在明确设置非生产连接、只读账号及两项启用标记时执行。封装脚本 `scripts/p0_starrocks_readonly_e2e.sh`，语法检查通过、未授权运行拒绝码 2。**真实 StarRocks 对账没有执行，不能把该测试存在或跳过视为通过 G4/P0-05。**
6. **运行环境说明**：README 增加 2026-10-13 现场核实告警，修正过去“生产已经在 MySQL”与当前实际 H2 服务不一致的描述，禁止按历史说明直接重启。

已提交核心文件：`AnalysisQueryPlanner.java`、`DatabaseStore.java`、`AnalysisApi.java`、`FieldCatalog.java`、`AnalysisQueryBoundaryTest.java`、`FieldCatalogRefreshTest.java`、`StarRocksReadOnlyReconciliationTest.java`、`AnalysisSubject.vue`、`AnalysisV2Chart.vue` 及相关脚本/CI。

**剩余发布门禁**：恢复生产 `runtime/local.env` 的合法配置、完成 H2 一致性备份并验证恢复；确认独立非生产 StarRocks 的只读账号和连接后启动只读真数对账；再隔离执行 P0-05 兼容/回滚检查。服务器到 GitHub 的 HTTPS 网络仍需单独处理，不直接覆盖现网工作树。

## 2026-10-13 本轮定向验证结果（追加）

- GitHub Actions [#131](https://github.com/NJgaoyang/insight-bi/actions/runs/38375685661) 的 `backend` 与 `frontend` 两个作业均显示 `success`。
- 独立克隆真实执行的后端 Maven 定向测试退出码 0，包括 `AnalysisQueryBoundaryTest` 的 500/501 行上限和 `COUNT DISTINCT`、`FieldCatalogRefreshTest` 的类型变化与稳定 ID。
- `StarRocksReadOnlyReconciliationTest` 的 Surefire XML 为 `tests=1, skipped=1, failures=0, errors=0`：**未执行真实 StarRocks**，不能用 Maven exit 0 代替真数对账。
- 隔离的 Vue/TypeScript/Vite 构建退出码 0；Chromium 多字段/COUNT DISTINCT 保存恢复及计算字段脚本均退出码 0。调用均为模拟 API。
- 检查完成后已关闭开发机隔离预览端口 `18117`。生产 `18110/api/health` 为 `{"ok":true}`，服务 active，生产工作树干净；未发布代码或重启生产服务。

## 2026-10-13 续开发：公式 AST、条件分支、查询并发与组件拆分

本轮根据“仅测试修改部分”执行定向验收：未运行全量 Maven 测试、旧 V1 页面回归或全部 Chromium 用例。所有新增代码提交 `feat/finebi-analysis-engine-p0`，未发布生产环境。

- **P0-03 公式**：新增 `CASE WHEN ... THEN ... ELSE ... END`（最多 8 分支）；支持受控的单引号转义字符串、`NULL/TRUE/FALSE`；比较双方和 `IF/NULLIF/COALESCE` 分支执行兼容类型检查，`MIN/MAX` 返回原字段类型。全 NULL/未知类型输出拒绝保存；公式仍禁止明细与聚合结果混用、未授权字段和危险函数。
- **公式 AST 契约**：`FormulaCompiler.Compiled` 提供纯 JSON 可序列化 `canonicalAst`，包括 `literal/field/binary/unary/call/case` 节点。仅在已验证的公式解析过程中构建 AST，不执行任意 SQL。公式预检 API `POST /api/datasets/{id}/calculated-fields/validate` 返回 `canonicalAst/phase/outputType/references/sqlPreview`。同时修复 API 原先 180 字符限制覆盖编译器 1500 字符合同的问题。
- **P0-04 公式编辑**：新增独立 `frontend/src/analysis/FormulaEditor.vue`，从 `AnalysisSubject.vue` 抽离对话框状态、公式验证与保存流程。支持“校验公式”但不保存，内联显示计算阶段、返回类型、字段引用数及受控错误；真正保存后通知父组件按当前数据集重新加载字段元数据，保留既有交互。
- **P0-02 并发保护**：独立 `AnalysisQueryLimiter` 通过公平 `Semaphore` 控制同时运行的 V2 JDBC 查询最多 4 个，最多等待 250ms，不在等待时占用连接，异常和查询结束都会归还许可。新增仅作用于 `AnalysisApi` 的 `AnalysisErrorHandler`：参数 400、缺失 404、并发 429、版本冲突 409、其余 JDBC/异常 502 并隐藏服务器内部消息；不改变旧 `BiApi` 的错误合同。
- **修改范围定向测试**（实际执行）：`FormulaCompilerTypedTest` 4 个测试、`AnalysisFormulaValidationTest` 1 个测试，后续仅 `FormulaCompilerTypedTest` 复测全 NULL 输出；`AnalysisQueryLimiterTest` 3 个测试。相关 Maven 指令均为 `mvn -q -o -Dtest=具体测试类 test`，执行退出码 0。
- **前端修改范围测试**：`npm run build`（Vue TypeScript+Vite）两次均退出码 0；只执行 `node scripts/finebi_formula_ui_smoke.cjs` 的 Chromium 模拟 REST 测试，验证错误公式、独立验证不写数据库、公式保存、刷新字段与重新打开，结果退出码 0。**未对其他界面做整站回归**。
- **仍阻塞**：P0-05 要求的非生产 StarRocks JDBC 真数对账、H2 元数据一致性备份与恢复测试、独立生产式预览发布和旧版本回滚演练，尚未执行，不能标记 G0/G4/P0-05 全通过。生产 `18110` 服务与其配置保持不变。

## 2026-10-13 增量：数据集变更防护、显式源别名迁移与字段可见性

所有修改仍在 `feat/finebi-analysis-engine-p0`；开发机仅使用隔离目录 `/opt/insight-bi-p0-preview`。**生产 `18110` 未部署、未重启；真 StarRocks 对账与权威元数据备份仍未完成。**

### P0-01：源字段稳定性

- 新增 `DatasetSchemaGuard` 并在 `POST /api/datasets` 的已有数据集更新前执行。比较提议的真实 SQL 返回列，拒绝删除被计算字段、草稿、已发布内容及历史版本引用的源列；拒绝使现有 SUM/AVG 或日期分组不兼容的类型变更。仅新增未使用的列可直接通过。涉及元数据更新的保存入口增加事务边界。
- **明确映射更名**：数据集编辑页面允许给出 `fieldRenames: {"旧源列":"新SQL输出别名"}`。后端严格验证旧列存在、新别名确实由新 SELECT 返回、新别名原先不存在、映射目标不重复，严禁猜测同义字段。显式映射保留 `insight_dataset_field.id`、显示名称及所有存储的公式/图表引用；不修改源数据库物理列。映射者须保证新别名与旧业务字段语义一致。
- **物理字段隐藏**：在 `insight_dataset_field` 增加 `hidden BOOLEAN NOT NULL DEFAULT FALSE`，旧表通过 JDBC 元数据检查进行增量 ALTER；同时修复其他已有字段的元数据大小写误判，使 H2 旧表、重复初始化和 MySQL 的表名约定更兼容。字段接口返回可选 `hidden`，`PUT /api/datasets/{id}/fields/{fieldId}/visibility` 仅改变展示状态，不改变稳定 ID、SQL 或公式权限。
- 分析主题字段面板隐藏未显示字段；勾选“管理隐藏字段”可查看并恢复，已隐藏行不可新增拖拽绑定；已有维度/指标槽绑定、公式及现有筛选仍保持可用。新建公式和筛选默认不建议隐藏字段。

### 只运行改动范围的验收证据

- `DatasetSchemaGuardTest`：**5/5 PASS**，覆盖公式依赖、草稿/已发布/历史引用、数值与日期类型、显式别名 ID 保留。
- `DatasetAliasUpdateIntegrationTest`：**1/1 PASS**，使用隔离 H2 真实 JDBC 元数据与模拟源连接验证 `POST /datasets`：未映射拒绝、明确映射成功、SQL/字段 ID 保存。
- `FieldVisibilityTest`：**2/2 PASS**，旧表加列并重复初始化的幂等性、隐藏/恢复及对既有查询与公式的兼容。
- 前端受影响的 Vue/TypeScript/Vite `npm run build` **PASS**；仅运行相关的 `finebi_dataset_alias_ui_smoke.cjs` 和 `finebi_field_rename_ui_smoke.cjs`，真实 Chromium 搭配模拟 REST，分别验证弹窗映射保存和字段隐藏/恢复后仍能读取原图表绑定，**均 PASS**。
- 为遵守“只测试修改部分”，把 `.github/workflows/p0-analysis-checks.yml` 变为基于 Git diff 的文件范围选择：Java 仅运行相关测试类，前端变更才构建，脚本仅语法检查，纯文档修改不跑业务测试。`scripts/p0_scoped_ci.sh` 已通过 `bash -n` 与临时 Git 仓库“只改公式 / 只改文档”模拟；工作流 YAML 已解析。GitHub Actions 最新运行仍需独立确认。

**未完成与限制**：P0-00 生产 H2 的事务一致性备份及恢复演练；P0-05 非生产 StarRocks JDBC 真数、最小只读权限及真实慢查询验证；生产 `runtime/local.env` 缺失与 GitHub 网络同步异常；尚未发布，不能据这些定向测试判定 P0 完成。

## 2026-10-13 继续推进：V1 兼容、查询只读、H2 恢复演练及 P0 发布门禁

本批功能只提交到 `feat/finebi-analysis-engine-p0`，仅在 `/opt/insight-bi-p0-preview` 执行本次涉及的 Maven 测试和 Bash 模拟；**生产 `18110` 未重启、未部署，生产 H2 未更改**。

- **遗留 V1 兼容修复**：`DatasetSchemaGuard` 的 V1 草稿、已发布快照、历史版本检查扩展到 `dimension/measure/secondMeasure/columnDimension/sort`、`columns/drillDimensions/pivotMeasures` 和旧筛选字段。因为这些数据仍直接引用源列名，明确的旧→新 SQL 别名映射不能安全地自动改写旧快照，所以在存在 V1 引用时拒绝更名。V2 保存配置使用候选字段重新编译，检查明细 WHERE、聚合 HAVING、维度粒度、聚合类型和排序在源列变更后是否仍合法。对应 `DatasetSchemaGuardTest` **8 个测试，exit=0**。
- **V2 JDBC 只读 fail-closed**：新增 `ReadOnlyConnectionPolicy`，每次新 V2 查询开始前尝试 `setReadOnly(true)` 并核对 `isReadOnly()`，驱动拒绝、忽略或报 SQLException 则拒绝执行且不会调用查询函数。原 V1 查询合同未改；此检查**仍不是数据库权限隔离的替代品**。对应 `ReadOnlyConnectionPolicyTest` **2 个测试，exit=0**。
- **H2 一致性备份/恢复机制的隔离验证**：`MetadataRecoveryRehearsalTest` 在临时 H2 文件型数据库执行真实 `BACKUP TO`，生成 ZIP、校验 SHA-256，解压后用独立连接恢复，核对字段 ID/隐藏属性、公式、数据集 SQL、发布快照与历史版本，**1 个测试，exit=0**。仅测试库；**并未对现网 H2 获取一致性备份**。
- **可执行的恢复核验和发布检查**：新增 `scripts/P0MetadataRestoreVerify.java`、`p0_verify_h2_restore.sh`，只读打开私有 ZIP 恢复到临时目录后的 H2 副本，验证必要元数据表，再签发 SHA-256 凭据；`p0_release_preflight.sh` 验证真实 ZIP/凭据、实际未跳过的 StarRocks Surefire XML、服务器环境/JAR、生产健康。发布时必须同时有显式 `P0_APPROVE_DEPLOY=true`。
- **减少停服风险**：`scripts/deploy.sh` 改为先完成前端构建与独立 Maven 打包，再备份旧 JAR、停服切换并执行健康检查；故障时尝试自动回滚旧 JAR。**未执行生产发布/回滚演练**。
- **真实 StarRocks 测试准入**：`p0_starrocks_readonly_e2e.sh` 默认拒绝，无专用非生产只读账号、缺启用标记或使用 root/admin 账号均拒绝。JUnit 内部重复检测账号和 JDBC 只读状态；脚本仅在测试实际运行、`tests>0` 且 `skipped=failures=errors=0` 时才归档 XML。**本次仅编译检查，真实 StarRocks 测试仍为 skipped，不能算通过**。
- **仅变动范围的实际验证**：`DatasetSchemaGuardTest`、`ReadOnlyConnectionPolicyTest`、`MetadataRecoveryRehearsalTest` 分别定向 PASS；`test_p0_release_safety.sh` 在纯合成 H2 数据验证恢复凭据、模拟成功验收报告与缺省/篡改/跳过拒绝条件 PASS；`deploy.sh` 未授权退出码 2，现网 `18110/api/health` 仍返回 `{"ok":true}`。所有新脚本通过 `bash -n`，Java 恢复检验器编译运行成功。**无全量回归**。

**P0-05 仍未通过的实际阻塞**：生产 `runtime/local.env` 缺失；生产 H2 一致性备份与恢复没有执行；独立非生产 StarRocks 的真实 JDBC 只读账号及对账尚未提供/启用；生产式隔离部署及回滚演练没有进行。参见 `docs/P0_RELEASE_GATE.md`；未满足前不会发布到 `18110` 或合并成正式完成。

### GitHub Actions 修复记录

GitHub Actions [#304](https://github.com/NJgaoyang/insight-bi/actions/runs/38398263323) 曾在 `AnalysisQueryBoundaryTest` 失败：H2 MySQL 兼容测试连接不会报告 JDBC 只读状态，而新的 V2 查询门禁会正确拒绝这个连接。已仅调整该 H2 查询边界用例，模拟支持只读的 JDBC 状态；拒绝非只读连接的生产策略没有放宽，并由 `ReadOnlyConnectionPolicyTest` 单独验证。开发机定向 `AnalysisQueryBoundaryTest` 通过；GitHub Actions [#305](https://github.com/NJgaoyang/insight-bi/actions/runs/38399054252) **success**。修复提交 `15f6182`。

## 2026-10-13：接入 DataForge 专用 StarRocks 测试环境

用户确认 **DataForge 的 StarRocks 为专用测试环境**，允许本项目复用其测试连接，不必另外新建 StarRocks 集群。已在 P0 真数对账工具中加入明确受限的测试环境入口，原正式环境仍禁止管理员凭据。

- FE 候选地址：`172.17.0.12:9030`（同局域网优先），`81.69.15.136:9030`（备用公网）。执行前要与 DataForge 当前配置核实；脚本默认使用 `jdbc:mysql://172.17.0.12:9030/ods`。
- 通过 `scripts/p0_dataforge_starrocks_e2e.sh` 注入 `DATAFORGE_STARROCKS_PASSWORD` 等运行时环境变量，设置 `P0_STARROCKS_E2E=true`、`P0_STARROCKS_NONPROD_CONFIRMED=true`、`P0_DATAFORGE_STARROCKS_TEST=true`；不会把真实账号密码写入 Git。
- 在 Java 对账测试和 Bash 入口双重限制：root/admin 只可用于已明确批准的上述 DataForge FE，其他地址仍拒绝；测试 SQL 仍只进行 SELECT/UNION ALL 合成数据查询，保留 JDBC 只读校验及非跳过报告核验。
- `DataForgeStarRocksTestAccessTest` 3 项定向权限与地址校验由 GitHub Actions [#321](https://github.com/NJgaoyang/insight-bi/actions/runs/38402539719) **success**；DataForge 专用启动脚本的增量语法工作流 [#322](https://github.com/NJgaoyang/insight-bi/actions/runs/38402709639) **success**。没有执行其他模块全量回归。
- **实际阻塞**：开发机 Remote Desktop Commander 在设备列表中显示在线，但执行命令、Ping 和文件同步连续超时；运行本环境的模型容器到这两个 FE 地址的 TCP 9030 均显示连接拒绝，不能替代开发机局域网连通性检查。因此截至本次仍**未获得真实 StarRocks JDBC 查询结果或非跳过报告**，P0-05/G4 不可视为完成。不要使用合成 SQL 测试或脚本语法通过冒充真实对账。

## 2026-10-13 后续增量：历史快照间接依赖保护与真实对账矩阵

用户要求仅运行修改范围的测试，继续沿 `feat/finebi-analysis-engine-p0` 推进 P0，保持生产 `18110` 不变。

- **计算字段依赖安全**：新增 `FormulaSnapshotGuard`，遍历当前数据集计算字段的直接与传递依赖；当被修改公式的下游计算字段已经用于已发布仪表板或历史版本时，一样拒绝影响历史语义的修改。删除计算字段时同时保护公式依赖、草稿、已发布、历史引用。快照采用结构化 JSON 的 `fieldId/formulaId/fieldIds/formulaIds/expression` 引用检查，不再把标题、说明中的偶然 UUID 子串误判为真实引用；无法解析的历史快照安全拒绝。保留旧 `AnalysisFormulaDependencyTest` 的兼容行为。
- **定向验收**：新增 `FormulaSnapshotGuardTest` 4 个用例：间接已发布依赖、纯文本误判、草稿引用删除、损坏快照的安全拒绝；`scripts/p0_scoped_ci.sh` 只在 `AnalysisApi` 或 `FormulaSnapshotGuard` 变动时运行相关的公式/依赖测试。GitHub Actions 对应定向作业已显示 **success**，没有执行全套回归。
- **StarRocks 手写 SQL 真数基准**：`StarRocksReadOnlyReconciliationTest` 新增独立手写 SELECT 进行城市与月份多维聚合、利润率对比；追加重复上海记录、`NULL` 金额/日期，检查 `COUNT`、`COUNT DISTINCT`、`SUM` 以及 NULL 与零值语义。该测试仅构造内联 SELECT 数据，不操作任何业务表。GitHub Actions 定向编译检查 **success**。
- **真实测试仍未完成**：DataForge 测试 FE 候选 `172.17.0.12:9030`；开发机设备目录偶尔显示在线且 Ping 最终返回，但远程 shell 一直超时，所以没有得到真实 JDBC 对账输出。**JUnit 被跳过的集成测试不能当作真库验收通过**。正式发布要求的生产 H2 备份和恢复验证仍未完成。

变更对应主要文件：`analysis/FormulaSnapshotGuard.java`、`analysis/AnalysisApi.java`、`analysis/FormulaSnapshotGuardTest.java`、`analysis/StarRocksReadOnlyReconciliationTest.java`、`scripts/p0_scoped_ci.sh`。本次所有提交均在开发分支，不合并 `main`，不部署生产。

## 2026-10-13 增量：DataForge 测试 FE 联通检查与 P0-05 SQL 夹具验收

在开发机 `VM-0-13-tencentos` 上实际核查：`172.17.0.12:9030` TCP **open**；`insight-bi.service` **active**、生产 `18110/api/health` 返回 `{"ok":true}`、生产 Git 工作区 **0** 改动、预览端口 `18117` 已关闭。

- **真实 StarRocks JDBC 仍未验收**：端口联通不等于 JDBC 认证成功。现有 DataForge 部署配置中的可用元数据密码未能解析，源端 `mysql` 空密码尝试被拒绝，尚未取得可安全注入的 DataForge StarRocks 测试连接凭据；未对 DataForge 业务表执行查询或变更。不标记 G4/P0-05 完成，不为认证绕过开放接口。
- **P0-05 SQL 夹具对账代码**：新增 `StarRocksInlineFixture.java`，H2 本地语义测试与真实 StarRocks opt-in 用例共享完全相同的内联 `SOURCE/EDGE_SOURCE` SQL。两边覆盖 4+2 行（北京、上海、南京、多笔重复数据、NULL 日期与收入），真实 StarRocks 用例继续提供独立手写 SQL 基准，不在数据库中建表或修改数据。
- **本次只测修改部分**：隔离工作目录 `/opt/insight-bi-p0-preview` 运行 `mvn -q -o -Dtest=StarRocksInlineFixtureTest test`，**2 tests/0 failures/0 errors/0 skipped**。验证 `COUNT`、`COUNT DISTINCT`、`SUM`、NULL 日期以及 WHERE 与 HAVING 差别。H2 列别名会变大写，本地测试将标签小写化，既不改变正式查询规划器也不改变 StarRocks JDBC 用例。
- **历史计算字段快照边界**：`FormulaSnapshotGuard` 对旧快照中内联表达式的 `[fieldId]` 引用增加引号、反斜杠与注释扫描。仅引用表达式实际使用的字段时才阻止公式修改，普通字符串常量不误判。开发机同步更新了 `AnalysisApi` 后，独立运行 `mvn -q -o -Dtest=FormulaSnapshotGuardTest test`，**6 tests/0 failures/0 errors/0 skipped**。
- **CI 调整**：`p0_scoped_ci.sh` 在共享 SQL 夹具变化时只选择 `StarRocksInlineFixtureTest`；相关 GitHub Actions 定向检查通过，未执行无关的全量回归或线上部署。

**剩余发布阻塞不变：** 需要 DataForge StarRocks 有效的 JDBC 身份信息以执行真实只读对账；生产 H2 一致性备份与独立恢复、生产式隔离部署及回滚演练均未执行。在这些门禁有有效证据之前保留 P0-05 未完成。生产 `18110` 保持不变。

## 2026-10-13：按使用者要求暂缓 StarRocks，转入 P1-01 增量

使用者要求将 **DataForge StarRocks 真实 JDBC 对账暂时搁置**，继续执行其他计划。P0-05/G4 及生产 H2 备份、恢复、回滚门禁**仍未完成、不视为自动通过**，生产服务 `18110` 未部署或重启。

本轮在现有 `feat/finebi-analysis-engine-p0` 分支开展 P1-01 兼容性底座：

- 结构化主题 → 组件 → 数据集资源图与 `GET /api/dashboards/{id}/resources`；草稿、发布快照、历史版本的数据集删除保护，去除原始 JSON 字符串误判。
- 主题 `content.dashboards[]` 多仪表板视图：每个视图按稳定 `widgetIds[]` 复用当前主题已有组件；前端可新建、重命名、切换、删除视图并多选组件。无视图属性的旧分析主题默认按单视图全部组件展示，普通仪表板历史合同不修改。
- 保存时验证资源引用、重新生成索引；发布前校验，避免无效数据集及悬空组件进入新版本；不新增数据库表。
- 只运行 `AnalysisResourceGraphTest` 与相关 `FineBiWorkspaceFolderTest`，隔离 Vue/TypeScript 构建和 `finebi_theme_views_ui_smoke.cjs` Chromium 测试；**全部成功，未执行全量回归**。CI 规则加入新测试映射。
- 详细实现证据和 P1-01 未完成部分见 `docs/P1_IMPLEMENTATION_STATUS.md`。

### P1-01 已发布查看与版本恢复补充

`App.vue` 中原 `#/view/{id}` 统一进入旧 `Designer`，无法显示已发布分析主题的 V2 多视图。新增 `PublishedReport.vue` 并按服务端 `kind` 分流：分析主题通过已发布快照只读展示多个 `dashboards[]` 和 V2 图表；普通旧仪表板仍使用原 `Designer readonly`，不改变其编辑或发布合同。旧分析快照没有多视图属性时维持单仪表板兼容。增加 `finebi_published_views_ui_smoke.cjs` 定向浏览器测试，PASS。

还补充版本恢复测试：原有 `/versions/{version}/restore` 直接恢复完整多视图快照和稳定组件引用。开发机 `AnalysisResourceGraphTest` **7/7 PASS**、相关 `FineBiWorkspaceFolderTest` **4/4 PASS**；前端 `vue-tsc`、Vite 构建及编辑、发布两个独立 Chromium 脚本 PASS。生产 `18110` 健康、无工作树改动；开发预览 `18117` 已关闭。P0-05 StarRocks 真数与生产 H2 备份门禁继续暂缓且未完成。

### P1-01 视图独立布局补充（StarRocks 仍按要求暂缓）

视图 `dashboards[].layout` 现在可按组件 ID 保存 6/12 列宽与 320/480/640px 高度，`widgetIds[]` 为显示排序依据；多视图共享分析组件的字段、公式与查询配置但各自保存布局。后端强制校验布局的目标组件必须属于当前视图、宽高必须为有效整数；前端支持调整顺序、宽高、保存后恢复、已发布查看和移动端单列。既有无布局的旧主题继续原默认显示。

开发机隔离定向 `AnalysisResourceGraphTest` **8/8 PASS**、Vue/TypeScript 构建 PASS、`finebi_view_layout_ui_smoke.cjs` 及原多视图编辑/已发布路由两个相关 Chromium 测试 PASS；生产 `18110` 服务 active、健康 `{"ok":true}`，无 Git 工作区改动，预览 `18117` 已关闭。未执行整站回归、未部署生产、未执行真实 StarRocks 对账。详见 `docs/P1_IMPLEMENTATION_STATUS.md` 第四批。

### P1-02 增量开发记录：数据加工步骤引擎第一批

按用户要求暂缓真实 StarRocks 后，继续开展 P1-02 的独立部分：新增有序步骤编译器和 `/api/datasets/{id}/pipeline/{compile|preview}`，行公式、类型安全明细过滤、最多一次分组汇总；新增独立元数据表、版本 CAS 存储和原 SQL 指纹，源 SQL 被修改后旧配置禁止直接预览，需重校验保存。公共数据新增“加工”入口、步骤排序/编辑/SQL 预览/真实数据预览及保存重开。详情在 `docs/P1_IMPLEMENTATION_STATUS.md`。

隔离开发环境定向测试 6/6 PASS、Vue/TypeScript 构建 PASS、Chromium 页面脚本 PASS；生产 `18110` 没有部署和修改，真实 StarRocks、生产 H2 备份/恢复与发布回滚门禁**仍未完成**。尚未实现 JOIN/UNION 与全功能可复用加工数据集，不可将 P1-02 整阶段标记通过。

## 后续优先事项

1. SQL 函数白名单真实兼容验证与数据库最小权限（仍阻塞生产级安全验收）。
2. 真实 StarRocks E2E 与慢查询/参数边界验证。
3. 计算字段依赖、更多函数和精度规则；字段变更时的引用迁移。
4. 组件分析 V2 浏览器拖拽、筛选、保存重开测试和撤销/重做。
5. 完成任务书 P0-05 后再把开发分支合并到主分支。
