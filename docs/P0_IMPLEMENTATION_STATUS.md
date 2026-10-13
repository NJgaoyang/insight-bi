# FineBI 自助分析计算引擎 P0 — 开发记录与交付说明

更新：2026-10-13；分支：`feat/finebi-analysis-engine-p0`。**本文件记录真实实现进度，不代表整个 P0 已验收完成。**

## 总体状态

| 任务 | 当前状态 | 已提交代码 | 未完成的验收 |
|---|---|---|---|
| P0-00 | **部分完成** | GitHub 分支建立，旧代码/API 检查完成 | 由于实行“GitHub 开发、自行拉取部署”，生产 MySQL/JAR 备份和独立预览环境由部署者操作；本次未操作远程服务器 |
| P0-01 | **核心代码完成，迁移待线上验证** | `FieldCatalog`、MySQL `insight_dataset_field` 和 `insight_calculated_field` 两张增量表、`GET /api/datasets/{id}/fields` | 真实 StarRocks JDBC 类型元数据与旧数据持久化验收、字段重命名引用迁移 |
| P0-02 | **核心代码完成，AST 只读校验已加入，生产安全尚需审计** | `AnalysisQueryPlanner`、`POST /api/analysis/v2/compile`、`.../query`；最多 3 维度/5 指标；分组、日期粒度、排序、结果上限 | JSqlParser SELECT 校验外已增加首批 SQL 函数白名单，未知调用拒绝；仍需 StarRocks 真实兼容性、只读权限、查询取消与并发限制验收 |
| P0-03 | **基础公式与前后过滤完成，高级语义待完善** | `FormulaCompiler`、行级/聚合阶段、WHERE/HAVING、参数绑定、公式新增/修改/删除/验证 | 已补公式更新的自引用、循环及依赖阶段检查；仍缺完整类型推断、复杂表达式依赖管理和 CASE/日期/窗口能力 |
| P0-04 | **基础 UI 已接入，浏览器联调待执行** | 新组件 V2 字段列表/多槽拖拽、计算字段输入、过滤、排序、图表渲染与保存；原 V1 组件保留 | 多字段与公式 UI 的模拟 API 浏览器测试已通过；仍缺真实 StarRocks E2E、完整按指标样式及其他图表边界验收 |
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

## 后续优先事项

1. SQL 函数白名单真实兼容验证与数据库最小权限（仍阻塞生产级安全验收）。
2. 真实 StarRocks E2E 与慢查询/参数边界验证。
3. 计算字段依赖、更多函数和精度规则；字段变更时的引用迁移。
4. 组件分析 V2 浏览器拖拽、筛选、保存重开测试和撤销/重做。
5. 完成任务书 P0-05 后再把开发分支合并到主分支。
