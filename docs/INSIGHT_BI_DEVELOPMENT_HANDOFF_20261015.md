# Insight BI 开发交接文档（2026-10-15）

> **用途**：交接给本地 AI / 新接手的全栈工程师，直接在现有代码上继续开发。本文是基于当前 GitHub 代码、已通过的定向验收及 2026-10-15 开发机现场核查整理的**当前状态快照**。早期开发计划和状态文件保留作历史参考，若与本文冲突，以**实际源码、当前分支和最新验收**为准。
>
> **仓库**：https://github.com/NJgaoyang/insight-bi  
> **唯一目标开发分支**：`feat/finebi-analysis-engine-p0`  
> **本次交接核对的代码基线 SHA**：`f14ffaf8657fc707232896cdf3909a85f1c7d6ab`（交接文档提交前）。  
> **阶段结论**：P0 核心分析引擎、P1 数据加工和仪表板基础、P2 治理与高级分析、P3 多项可靠性验收已实现**具体子功能**；本轮已转回产品功能开发，刚完成「统一分析工作台·第一批」。**整个 FineBI 式 BI 产品尚未完成，最新分支未部署到现有在线服务。**

## 1. 目标、技术栈、不可变约束

**业务目标**：独立自研一个使用体验接近 FineBI 6 的企业自助分析系统，主路径为「我的分析 → 分析主题 → 公共数据/已有数据集 → 组件分析 → 仪表板 → 发布/授权查看」。交互可参考公开/授权使用的产品，但**不得复制 FineBI 专有源码、JAR、素材、字体或品牌**。

- **前端**：Vue 3、TypeScript、Element Plus、Apache ECharts、GridStack/Moveable、Vite。中文企业级简约高密度界面，减少无效说明和模拟功能。
- **后端**：Java 21、Spring Boot 3 单体应用、JDBC；旧 `/api/query` 与 V2 `/api/analysis/v2/*` 并存。
- **业务查询**：优先 StarRocks JDBC 直连，业务明细留在 StarRocks；必要时兼容 MySQL 数据源。由服务器验证只读 SQL、计算字段、稳定 field ID 与结构化参数，并在数据库中执行聚合；不允许前端拼 SQL 后直传。
- **元数据**：已有 H2/MySQL 兼容模式及相应表结构；不能因目标架构建议 MySQL 就假设现有线上数据已经迁移 MySQL。
- **严格兼容**：旧 `Designer.vue`、V1 Widget/报表 JSON、已有目录、版本历史、已发布快照及读者授权必须可读。禁止无备份的自动迁移、覆盖、删表和服务重启。
- **非目标**：内置演示报表/假数据、重新建设 ETL/SeaTunnel 调度、偷偷跨数据源搬运大量业务明细、照抄商业 BI。
- **开发方式**：在本分支直接修改和提交；先验证现存代码/路由，已经修好的功能不重写；**只测试修改区域，不做全量回归，也不无限轮询 CI**。每批必须有可用入口、真实处理逻辑、保存/恢复和定向验收，不接受只有按钮的占位实现。

## 2. 开发机与部署状态：务必先看

| 对象 | 2026-10-15 核实结果 | 接手注意事项 |
| --- | --- | --- |
| GitHub 开发分支 | `feat/finebi-analysis-engine-p0`，基线代码 `f14ffaf8657f` | **最新开发代码来源**，提交交接文件后分支 HEAD 会再前进 |
| 授权开发机 | `VM-0-13-tencentos`，项目路径 `/opt/insight-bi` | 可通过已授权的开发机连接检查，不要猜测路径 |
| 开发机工作树 | 分支同名，但本地 HEAD **`823dbdb`**，`git status --porcelain` 为空 | **落后于 GitHub**；未授权部署前只允许安全同步、隔离构建，不要 `reset --hard` |
| 当前运行服务 | `insight-bi.service` 为 **active**（现场 MainPID 993） | 这只能证明服务仍在运行，不证明运行的是 GitHub 最新代码 |
| 线上元数据 | systemd 现场可见 `jdbc:h2`，历史记录指向 H2 文件元数据 | 正式迁往 MySQL 前必须做**一致性备份、空库恢复演练、数据核验和回滚计划** |
| 原线上入口 | 历史配置为 `18110` | 未核实代理/TLS 时不可将其称为已完成的安全 HTTPS 部署 |
| 真实 StarRocks | 曾在开发机对指定 FE 完成真实 JDBC V2 与临时 OLAP 表只读账号测试 | 这些只证明专项用例通过；没有批准的故障窗口不得人为重启 FE/BE |
| 秘钥与密码 | 不保存在本仓库交接文件内 | 用本机私密环境配置/秘密管理，严禁将明文写入 Issue、CI 日志和 Git |

**严禁将 GitHub CI 通过视为生产部署完成。** 目前开发分支和运行中服务版本存在差距。早期 `README.md` 和旧交接含历史配置/待办；**不要直接套用其中声称 MySQL 已上线、P0 未开发、只有单管理员之类的过时表述**。

## 3. 已实现的核心模块（是“具备功能”，不是整个模块 100% 完成）

| 模块 | 当前可复用的功能 | 关键实现 |
| --- | --- | --- |
| 公共数据/连接 | MySQL/StarRocks 数据源配置、连接测试、数据集 SQL、字段发现与预览、目录管理、字段别名和计算字段基础 | `App.vue`、`DatabaseStore.java`、`FieldCatalog.java` |
| V2 自助分析 | 最多 3 维度/5 指标、聚合、ROW 与 AGGREGATE 公式、明细/汇总过滤、排序与 Top N、日期粒度、参数化安全 SQL | `analysis/AnalysisApi.java`、`AnalysisQueryPlanner.java`、`FormulaCompiler.java` |
| 数据加工 P1-02 | 可保存的顺序加工步骤、公式/条件列、过滤、分组、同连接 JOIN 与 UNION、加工结果复用于分析、版本约束 | `analysis/AnalysisPipelineApi.java`、`PipelineEditor.vue` |
| 组件与主题 | V2 图表、字段拖拽、多指标样式、CSV 结果导出、基本明细表；一个主题共享组件、多仪表板视图和布局 | `AnalysisSubject.vue`、`AnalysisV2Chart.vue`、`dashboardLayout.ts` |
| 旧仪表板兼容 | 旧 `Designer.vue` GridStack/Moveable、旧 ECharts/基础透视表/筛选/下钻等保留原路由；不得擅自改格式 | `Designer.vue`、`ChartWidget.vue`、`PublishedReport.vue` |
| 高级计算 P2-01 | 排名、占比、维度累计、日期同比/环比基础、表格隐藏列持久化及发布快照约束 | `AnalysisApi.java`、`AnalysisV2Chart.vue` |
| 权限与发布 P2-02 | 管理员/VIEWER、用户部门、报表授权及部门继承、目录 ACL、部分行列权限、已发布只读查询、发布审批留痕 | `GovernanceApi.java`、`ReaderApi.java`、`GovernanceAdmin.vue` |
| 可靠性 P3 | 元数据加密备份与空库恢复校验、就绪/运行监控、JDBC 连接熔断、查询超时 504、4 并发/250ms 等待、慢查询统计 | `MetadataBackupService.java`、`OperationsApi.java`、`analysis/SourceRecoveryGuard.java`、`AnalysisExecutionMetrics.java` |

**不要误解**：高级交叉表、完整图表属性、跨数据集语义建模、灵活联动、真正多人编辑/独立审批、批量迁移和线上高可用等**仍没有完整交付**。部分功能只在 V1 实现、V2 尚不具备，不能因为旧页面存在按钮就宣称新主题完成。

## 4. 最新完成：统一分析工作台第一批（当前交接点）

**代码基线 `f14ffaf` 已完整包含本批：**

1. 直接在 `AnalysisSubject.vue` 的主题仪表板画布，拖动组件手柄调整**当前视图**中的顺序，另一视图不会被污染；
2. 在画布选中组件并进入既有 V2 组件编辑入口，数据、样式修改作用于**同一个共享 Widget 对象**，不是生成第二份配置；
3. 「复制视图」保留组件引用、排序和宽高，复制独立视图 ID、布局元数据；**组件本身、数据集和 SQL 不复制**；
4. 旧版 `Designer.vue` 和 V1 报表继续走旧入口，未强制迁移；新编辑保存仍走原 `PUT /api/dashboards/{id}`、修订号和发布快照；
5. 视图层的无效拖拽/悬空布局引用保护、撤销/重做与已发布只读画布兼容。

**文件**：
`frontend/src/AnalysisSubject.vue`、
`frontend/src/analysis/dashboardLayout.ts`、
`frontend/src/style.css`、
`scripts/analysis_workspace_layout.test.mjs`、
`scripts/analysis_workspace_browser_smoke.cjs`、
`.github/workflows/analysis-workspace-scoped.yml`。

**已验证**：

- 视图布局纯逻辑 + 独立 Chromium（拖拽、复制、共享编辑、保存、刷新、发布）专项 CI **SUCCESS**：[Analysis Workspace Scoped #38695278698](https://github.com/NJgaoyang/insight-bi/actions/runs/38695278698)；
- 前端 `vue-tsc` 与 Vite 构建、最终当前分支代码改动检查 **SUCCESS**：[P0 Changed-Area Checks #38695421273](https://github.com/NJgaoyang/insight-bi/actions/runs/38695421273)；
- 本阶段说明：[UNIFIED_ANALYSIS_WORKSPACE_PHASE1.md](UNIFIED_ANALYSIS_WORKSPACE_PHASE1.md)。

**尚未实现**：把旧 V1 `Designer` 资源转换为 V2 stable fieldId 的无损自动迁移；完整自由布局/复杂表格；跨数据集可验证联动；真正的多人协作。本批**没有部署到在用 18110 服务**。

## 5. 可靠性与安全专项的已验证证据（不要无限重复跑）

| 场景 | 已有证据 | 准确结论 |
| --- | --- | --- |
| 真实 StarRocks V2/OLAP + SELECT-only 账号 | [P3_STARROCKS_SCOPED_OLAP_ACCEPTANCE.md](P3_STARROCKS_SCOPED_OLAP_ACCEPTANCE.md) | 开发机专用随机库、合成数据和专用只读账号通过，临时测试资源已清理；**不是业务库或生产高可用验收** |
| 一次性 MySQL JDBC SQL 取消 | [#38691832683](https://github.com/NJgaoyang/insight-bi/actions/runs/38691832683) | 25 秒合成 SELECT 在 15.023 秒时取消，同连接后续查询成功；这是 MySQL 8.4 测试条件 |
| 8 读者 / 60k 行 | [#38692527238](https://github.com/NJgaoyang/insight-bi/actions/runs/38692527238) | 24/24 查询成功、P95 139 ms，行权限/撤销通过；不是生产 SLA |
| 真实 HTTP 会话 + MySQL | [#38693468804](https://github.com/NJgaoyang/insight-bi/actions/runs/38693468804) | Spring Boot/Tomcat HTTP、8 Cookie 会话、CSRF、读者访问和撤销通过，16/16 请求成功，P95 72 ms |
| 元数据备份、恢复和运行监控 | [P1_IMPLEMENTATION_STATUS.md](P1_IMPLEMENTATION_STATUS.md) P3-01～03 | 具备加密备份与诊断功能，但正式部署环境的持续备份、告警、恢复演练仍未交付 |

**已修复的真实启动阻断**：`AnalysisPipelineApi` 曾因 `final` 与 Spring `@Transactional` 代理冲突导致启动失败；已修复并由 HTTP 启动级专项验证。不要重复再处理一次。

## 6. 后续待开发功能：按实际业务价值排序

| 优先级 | 任务 | 仍需开发的内容 | 验收标准 |
| --- | --- | --- | --- |
| **P0 / 第一批** | **V2 分组表/交叉表增强** | 从现有基础明细表与 V1 透视表抽象共享展示能力，新增 V2 行/列分组、多个指标、合计/小计、排序、格式；明确 3 维度/5 指标与行数边界 | 在真实可控的 StarRocks/MySQL 测试数据上验证多组别合计，保存/重开/发布一致，非 V2 旧表保持原样 |
| **P0 / 第二批** | **完善统一分析主题画布** | 更直观的图表放置/布局管理、选中设置联动、可控拖动与键盘可达、响应式布局、筛选组件嵌入主题；保留现有视图模型 | 同主题 2 个视图共用 3 组件，分别布局、保存/重进、发布快照/旧报表均无损 |
| **P0 / 第三批** | **数据加工编辑增强** | 中间步骤结果预览、步骤复制/重新排序/撤销与依赖提示、类型转换、复杂 JOIN 检查；无效步骤回滚，保持处理结果版本固定 | 有三步以上加工链，改中间步骤后输出 SQL/字段正确；坏配置不能保存/发布 |
| **P1** | 数据模型与跨数据集交互 | 可视化同源多表关系、稳定字段映射、模型影响分析、跨组件自定义字段映射、跨数据集过滤/钻取（需要安全的同源条件） | 数据集 A/B 依明确映射正确联动；空值/不兼容字段/无权限时 fail closed |
| **P1** | 高级组件/计算/导出 | 更多图表及其独有属性、复杂表格条件格式、完整日期窗口计算、受控 Excel/PDF 导出和完整查询结果策略 | 属性真实影响数据/图形；可持久化及导出；大结果不造成内存溢出 |
| **P1** | 编辑与分享协作 | 真正的 EDITOR/VIEWER 权限分离、资源分享、成员权限、真正双人审批（现有仅单管理员留痕）、资源迁移工具 | A 可编辑 B 只读；撤销实时生效，未经授权的发布/字段查询被拒绝 |
| **P2 / 部署门禁** | 安全、部署和运维 | 线上 HTTPS/Nginx 代理与 Cookie 策略、生产 H2→MySQL 迁移（如确有需要）、定期备份与空库恢复、告警/容灾、上线回滚 | 隔离环境通过备份恢复/验证；旧已发布报表可读；回滚可恢复原 JAR+元数据 |

**范围约束**：以上是推荐排序，不表示同一批必须全部完成。不要新增假的菜单或无处理逻辑的按钮。与其不断扩展 P3 合成数据压力测试，应优先把日常制表/分析流程补齐。

## 7. 推荐接手立即执行的下一项（小步可完成）

**任务编号：ANALYSIS-PHASE2-01 —— V2 交叉表/分组表第一批。**

1. **读现状**：核对 `AnalysisV2Chart.vue` 当前 `table` 的行列表格，以及 `ChartWidget.vue` 旧 `pivot` 已有多指标和合计功能，禁止重复从零设计；核对 `AnalysisQueryPlanner.java` 的 `dimensions/measures` 输出列及 LIMIT/Top N 语义。
2. **定协议**：以原稳定 fieldId 维度/指标为输入，明确行维度、列维度、多个指标、合计/小计与空值规则；**由后端执行数值聚合**，前端只做有限结果的透视展示与可视化布局。维度/指标预算和安全 LIMIT 沿用现有 V2 限制，不允许前端自由 SQL。
3. **先实现最小闭环**：V2 交叉表展示行×列×多指标、求和（注意不要对 AVG 等不可加指标简单求和）、合法空值与排序；组件编辑器选择类型、配置字段；保存/重进/发布快照复现。高级多层小计可以下一批再做，不能先挂一个不工作的入口。
4. **定向验证**：H2/一次性 MySQL 或授权只读 StarRocks 测试数据，验证数值、没有越权字段、无数据、字段重名、NULL、Decimal、多指标、分页/截断警告；只运行本次影响的 Java/TypeScript/Chromium 定向测试。
5. **交付门禁**：一批只提交相关组件+最小 SQL/元数据必要修改+定向测试+短验收文档。测试未通过就保留未完成标记，不进入下一批；绝不迁移现有真实业务表。

**明确不做**：先不要重写整个 `Designer.vue` 或强制转换旧报表；不要一上来做完整自由画布、100 种图表、数据抽取，也不要在未确认真实部署前直接把新版本发布到 `18110`。

## 8. 关键文件与参考文档索引

```text
FINEBI_DEVELOPMENT_EXECUTION_PLAN.md            # 原总计划，P0 初期描述有历史时效
docs/FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md   # FineBI 官方功能行为对照；不代表已实现
docs/P0_IMPLEMENTATION_STATUS.md                # 历史 P0 落地记录；以前的待办须重新核实
docs/P1_IMPLEMENTATION_STATUS.md                # P1/P2/P3 和统一画布各批提交与验收索引
docs/UNIFIED_ANALYSIS_WORKSPACE_PHASE1.md       # 最新已完成批次说明
docs/P3_HTTP_READER_SESSION_ACCEPTANCE.md       # 真实 HTTP 会话、CSRF、权限验收
docs/P3_STARROCKS_SCOPED_OLAP_ACCEPTANCE.md     # 真实 StarRocks OLAP 专项
backend/src/main/java/com/insightbi/
  BiApi.java / DatabaseStore.java / GovernanceApi.java / ReaderApi.java
  analysis/AnalysisApi.java / AnalysisQueryPlanner.java / AnalysisPipelineApi.java
frontend/src/
  App.vue / AnalysisSubject.vue / AnalysisV2Chart.vue / Designer.vue
  ChartWidget.vue / PipelineEditor.vue / PublishedReport.vue
  analysis/dashboardLayout.ts
scripts/analysis_workspace_layout.test.mjs
scripts/analysis_workspace_browser_smoke.cjs
.github/workflows/p0-analysis-checks.yml
.github/workflows/analysis-workspace-scoped.yml
```

## 9. 接手人实际操作顺序和安全指令

```bash
# 优先在隔离工作树/工作目录执行，先确认工作树无未保存变更
cd /opt/insight-bi
git status --short
git branch --show-current
git rev-parse --short HEAD
git remote -v

# 无冲突且确认不会影响线上工作树后，抓取最新分支（绝不强制重置）
git fetch origin feat/finebi-analysis-engine-p0
# 如开发机检出分支干净且能够快进：
git merge --ff-only FETCH_HEAD

# 仅在涉及前端改动时
cd frontend
npm ci --no-audit --no-fund
npm run build

# 本批画布纯逻辑检查（仓库根目录执行）
cd ..
node --experimental-strip-types --test scripts/analysis_workspace_layout.test.mjs
```

若线上 `insight-bi.service` 正在使用 `/opt/insight-bi` 的 JAR 或静态资源，**在未完成一致性备份和隔离验证前不得 `systemctl restart/stop`、覆盖 JAR、部署编译产物或切换元数据库**；建议采用单独检出目录进行开发和验证。若 GitHub 网络失败，一次失败就记录当前 SHA 和阻碍，避免无限重试。

**每批交付格式**（必须输出）：

- 当前任务编号与范围；
- 变动文件/新增 API/真实查询 SQL 语义；
- 兼容旧 JSON、发布快照与权限的影响；
- 定向测试（命令、通过/失败、CI 链接和未执行项）；
- 已知风险及下一批；
- GitHub 分支与完整 commit SHA；
- 是否进行了部署：默认 **否**。

## 10. 不可忘记的风险和未验收范围

1. **开发分支不等于线上版本**：开发机当前 HEAD `823dbdb`，GitHub 交接基线 `f14ffaf`；没有进行最新分支在线发布/回滚验收。上线前须明确现网 JAR、systemd 配置、H2 文件一致性备份和回滚路径。
2. **基础查询限制**：V2 3 维度、5 指标、行数上限及 JDBC 15 秒超时仍有驱动差异；V2 已有 4 并发+250ms 排队，新增大表/交叉表不得绕过这些保护。
3. **账号/权限**：之前的开发机 StarRocks 直连用过高权限账号完成连接测试，但验收已使用临时 SELECT-only 账号证明最小权限。未来正式服务仍应使用专用只读账号，不存 root 明文；读者权限须按已发布快照与 row/column/目录 ACL fail closed。
4. **过时材料**：早期 `README.md`、`P0_IMPLEMENTATION_STATUS.md` 和总计划的初始“未完成”表格包含历史进度，不能不核对源码就照单重复做。尤其 P2 权限与 P3 监控、P1 加工已实现多个完整子功能。
5. **缺失的验收**：HTTPS/Nginx/Cookie 真代理、跨节点会话、高可用与实际 FE/BE 故障演练、生产真实容量、完整旧资源迁移、独立 EDITOR 协作权限、生产数据备份/恢复方案，均不能标注已通过。
6. **版权/范围**：自主研发，不复制任何商业产品内部实现；不改动 DataForge、DataEase、Datart 或现有 FineBI 环境。

---

**接手结论**：代码基线不是空项目，下一步应直接从 **`ANALYSIS-PHASE2-01`：V2 分组/交叉表实用闭环** 开始开发，而不是重新做 P0 数据源、再建第三套工作台或反复跑 P3 可靠性测试。**测试修改区域 → 保存/发布兼容 → GitHub 提交 → 更新本文**，一批一交付。


## 11. 接手后的执行补充：ANALYSIS-PHASE2-01 第一批（2026-10-15）

在隔离工作目录 `/opt/insight-bi-phase2` 基于分支 `feat/finebi-analysis-engine-p0` 的 `da2344054` 开始，已实现 **V2 交叉表基础闭环**：两个稳定 fieldId 维度、1–5 指标的分组聚合展示、NULL 与同名列处理、行列排序、最多 100×24 分组安全限制、每页 20 个行组、完整结果的可加指标行列合计与总计；截断、AVG、DISTINCT 等非可加指标不显示虚假合计。保持现有 V2 SQL Planner、查询/权限/快照 API 和旧 V1 报表不变。

新增或调整：`AnalysisQueryPlanner.java` 的 `additive` 查询列元数据、`AnalysisV2Chart.vue`、`AnalysisSubject.vue`、`pivotTable.ts`、`api.ts`、样式和专项 H2/Node/Chromium 测试。定向检查：H2 Java 14/14 PASS、Node 4/4 PASS、TypeScript/Vite 构建 PASS、隔离 Chromium mock REST 保存/重进/两视图/发布/分页/截断 PASS。**没有进行全量回归、生产数据操作或 18110 部署**。

详细验收与限制见 [ANALYSIS_PHASE2_01_PIVOT_BATCH1.md](ANALYSIS_PHASE2_01_PIVOT_BATCH1.md)；下一批建议继续服务端 SQL ROLLUP 安全小计、多层行列维度和高精度数字传输，不要误判第一批等于高级交叉表功能全量完成。CI 的远端执行结果另行核验，不以本地测试替代 CI 结论。


## 12. 接手后的执行补充：ANALYSIS-PHASE2-02 第二批服务端精确合计（2026-10-15）

基于第一批交叉表实现，现已新增 `PivotRollup` 服务端分组重算：依旧经 V2 安全 Planner + 已发布读者的强制明细过滤，先执行原分组查询，再在同一个只读连接及限流许可内执行独立行合计、列合计、总计 SQL，正确求 AVG 和 COUNT DISTINCT，而非将分组平均值/去重数相加。用于合计的 DECIMAL 返回字符串以避免 JavaScript Number 精度损失。截断、后置过滤、窗口计算、行列组不一致均禁用合计；旧前端/旧 V2 查询仍保持原接口。未创建任何新元数据表，也未修改 V1 Designer。

定向 H2/JUnit 覆盖实际 API 和数据库 AVG/去重聚合、注入式读者过滤、出错与截断拒绝；TypeScript、Node 纯逻辑、独立 Chromium 展示/保存/发布/截断场景均属于本批范围。详细测试命令、兼容性、风险与下一批见 [ANALYSIS_PHASE2_02_PIVOT_ROLLUP.md](ANALYSIS_PHASE2_02_PIVOT_ROLLUP.md)。注意 4 次 SQL 非单语句快照一致性，也没有验证生产 StarRocks 的多语句一致性；未部署 18110，也不触碰原始业务表。第三批优先支持层次交叉表与多级小计。


## 13. 接手后的执行补充：ANALYSIS-PHASE2-03 第三批三维交叉表与逐列小计（2026-10-15）

在第二批精准 SQL 汇总之上，现支持两个或三个稳定 fieldId 维度的 V2 交叉表。三个维度的次序为「行一级 → 行二级 → 列一级」，后端在分组明细之外逐层计算叶子行合计、列合计、总计、一级行分组合计及「一级行分组 × 列」小计；所有 AVG、COUNT DISTINCT 必须由后端 SQL 重新计算。前端显示父级分组/子级明细以及父级结束的小计行，支持控制是否展示一级行小计，保留保存/撤销/发布快照及多个视图共用组件。

本批仅补足了**受控三维层级交叉表**，不是无限层级、多维列组或完整 FineBI 企业级大表。接口继续是已有 V2 查询/已授权发布读者查询，沿用 4 并发+250ms 等待、JDBC 单条 SQL 15 秒超时和 LIMIT+1 截断。多 SQL 不是跨语句严格一致快照，复杂结果过滤和窗口分析仍拒绝合计。所有测试均在隔离 H2、模拟 REST 和本地静态构建下完成，未启动/重启线上服务。

文件、专项测试命令与下一批待办参见 [ANALYSIS_PHASE2_03_PIVOT_HIERARCHY.md](ANALYSIS_PHASE2_03_PIVOT_HIERARCHY.md)。本批保留第一批和第二批的验收测试，且第三批专项 CI 通过才可标记远端验收成功。


定向兼容补充：本批隔离 Chromium 复验旧版已发布 V2/V1 只读视图及读者授权，发现旧脚本 `scripts/finebi_published_views_ui_smoke.cjs` 误用页面第一张表定位部门，已改为严格定位「部门管理」区块；专项测试通过。原有授权后端及页面代码均未因此修改。


## 14. 统一分析工作台第二批：键盘布局控制（2026-10-16）

键盘可在现有仪表板画布手柄上执行方向键/Home/End 顺序变更以及 Shift+方向键的有限宽高调整；仅修改当前视图引用和布局，旧 Widget 和旧 V1 Designer 不转换。新增快捷键说明及屏幕阅读器反馈；保留已有的拖动、撤销/重做、保存及发布语义。详细代码及验证门禁见 [UNIFIED_ANALYSIS_WORKSPACE_PHASE2_KEYBOARD.md](UNIFIED_ANALYSIS_WORKSPACE_PHASE2_KEYBOARD.md)。当前远程开发机显示离线，GitHub CI 结果需独立核验，未部署生产环境。


## 15. 数据加工编辑增强第一批（2026-10-16）

在已有步骤前后移和后端编译/保存能力上，新增有限安全复制（formula/case/filter）、中间步骤服务端数据预览与最多 30 步编辑撤销/重做。复制 formula/case 时重置输出 stable fieldId 并保留原始输入字段依赖；JOIN/UNION/GROUP 不允许复制。仍走原处理版本与后端校验，未部署线上。验收文档：[PIPELINE_EDITOR_PHASE2_BATCH1.md](PIPELINE_EDITOR_PHASE2_BATCH1.md)。远程开发机当前离线，使用 GitHub 专项 CI 核对本批，不等同于真实数据源验收。


## 16. 数据加工编辑增强第二批：安全类型转换（2026-10-16）

新增后端 `AnalysisPipelineCompiler` 的受控 CAST 步骤及前端类型转换配置，可以以新 stable fieldId 生成数字→定点数和数字/日期/布尔→文本字段，并供后续过滤/汇总使用。禁止字符串→数值的宽松静默转换，保留原数据字段；继续沿用旧的编译、真实数据预览、revision 保存与权限，不新增查询 API。见 [PIPELINE_EDITOR_PHASE2_BATCH2_CAST.md](PIPELINE_EDITOR_PHASE2_BATCH2_CAST.md)。H2/MySQL 模式的专用 Java 测试、Vue 构建、Chromium mock REST 测试加入专项 CI。当前开发机离线，**并未通过真实 StarRocks 驱动专门验收**，未部署线上服务。


## 17. 数据加工编辑增强第三批：JOIN 配置前置校验（2026-10-16）

增强数据关联配置的即时风险提示：同连接数据集、等值键存在和类型匹配、重复键、稳定输出字段 ID 和名称约束。关联键或 JOIN 方式变化会撤销之前的 `acknowledgeFanout`，明确要求用户重新确认；未通过则 UI 禁止保存，后端现有 JOIN 校验仍作为最终权威。纯前端迭代、不新建 API/表、不涉及线上部署。见 [PIPELINE_EDITOR_PHASE2_BATCH3_JOIN.md](PIPELINE_EDITOR_PHASE2_BATCH3_JOIN.md)。


## 18. 数据加工编辑增强第四批：步骤依赖影响检查（2026-10-16）

新增顺序式 stable fieldId 依赖检查，在编辑器中标注中间步骤被删/移动后哪些下游步骤字段失效。支持公式/CAST/条件/过滤/JOIN 输出/分组汇总的已知字段引用与 GROUP 字段收缩检查；拒绝保存失效草稿，完整预览被阻断但此前有效前缀仍可预览，撤销可恢复。仍由后端作为 SQL、权限及版本的最终裁决，不改旧资源或线上服务。专项验收见 [PIPELINE_EDITOR_PHASE2_BATCH4_DEPENDENCIES.md](PIPELINE_EDITOR_PHASE2_BATCH4_DEPENDENCIES.md)，只依 GitHub Actions 的定向检查，不宣称离线开发机的生产验收。


## 19. ANALYSIS-VIEW-FILTER-01：统一分析主题视图筛选（2026-10-16）

在现有仪表板画布保存模型中新增一个可配置的视图级 stable fieldId 筛选组件；只有同一数据集且 Widget 的维度槽明确绑定该字段时，应用的运行时值才能参与查询。筛选值不存档；Admin 作者预览及发布读者均使用原安全 V2 Query Planner，读者 API 仅接收 `viewId/filterValue`，服务器验证它们与已发布视图/组件/字段配置一致，再叠加行级 ACL，不允许传入 dataset/SQL/filters。详见 [ANALYSIS_VIEW_FILTER_01.md](ANALYSIS_VIEW_FILTER_01.md)；不代表跨数据集字段映射或多条件筛选已完成。开发机仍离线，只有 GitHub 专项 CI 能验证本批，生产 18110 未部署。


## 20. P1-CROSS-DATASET-MAPPING-01：同连接跨数据集显式映射（2026-10-16）

统一仪表板视图的筛选器现可逐个目标 Widget 配置另一数据集的显式 stable fieldId 映射，仅允许同一连接且服务端字段类型完全相同的物理维度，不再仅限同一数据集。前端保存映射但不保存运行时值；读者仅传 `viewId/filterValue`，服务端结合已发布配置重新校验目标 Widget/来源 Widget 的维度、连接、物理字段类型并叠加强制行权限。无映射、类型不匹配、跨连接均拒绝；旧同源筛选继续兼容。新增 H2、Node 与 Chromium 定向验收，详见 [P1_CROSS_DATASET_MAPPING_01.md](P1_CROSS_DATASET_MAPPING_01.md)。不代表实现跨数据集 SQL JOIN、完整语义模型或多字段钻取；开发机离线、未部署线上 18110。


## 21. ANALYSIS-VIEW-FILTER-02：受控双条件 AND 筛选（2026-10-16）

在原视图 filter 主字段与跨数据集显式映射的基础上，新增可选 filter2 同一数据集的不同维度，运行时值互不干扰，并在 Widget 同时绑定相应维度时作为第二个 AND 条件叠加。读者 API 保持旧的空请求和单值协议，按发布快照核验后才可使用 `filterValue2`（也支持只有第二条件的视图查询），禁止任意 SQL/字段和超范围输入；行级强制过滤仍附加。详见 [ANALYSIS_VIEW_FILTER_02.md](ANALYSIS_VIEW_FILTER_02.md)。本批不提供任意多条件/OR 或第二字段跨数据集映射，开发机仍离线，未部署线上 18110。


## 22. PIPELINE-RELATION-GRAPH-BATCH1 · 现有加工链关系可视化（2026-10-16）

沿用现有一次同连接 JOIN/UNION 的步骤配置，增加只读关系概览：左右数据集、关联键及类型、输出字段、依赖这些字段的下游步骤计数、行数放大风险提示。关系图不是新的 SQL 编译器，编辑、保存及权限继续由原数据加工逻辑/服务端控制。详见 [PIPELINE_RELATION_GRAPH_BATCH1.md](PIPELINE_RELATION_GRAPH_BATCH1.md)，并纳入 pipeline-editor scoped Node、Chromium 和 TypeScript 定向测试。尚未实现任意多表关系语义模型或跨连接 JOIN；开发机离线，未部署线上 18110。


## 23. ANALYSIS-DRILL-01：受控视图点选钻取（2026-10-16）

继承旧 jumps，新增显式 drills 开关；同数据集稳定维度才能从来源 Widget 向已配置的目标视图筛选字段传值。运行时值不存档，读者继续受后端发布配置/权限检查约束。具体见 [ANALYSIS_DRILL_01.md](ANALYSIS_DRILL_01.md)。开发机离线，未部署线上。


## 24. PIPELINE-RELATION-IMPACT-BATCH2：关系字段影响链（2026-10-16）

在 JOIN 关系可视化基础上，按 stable fieldId 提示右表输出字段在后续过滤、公式、条件转换、分组汇总中的直接及派生引用，GROUP 后的不可见原始字段不错误传播。面板只读，不修改 SQL 编译规则及权限，详见 [PIPELINE_RELATION_IMPACT_BATCH2.md](PIPELINE_RELATION_IMPACT_BATCH2.md)。增加 Node / 浏览器定向检查；未部署线上。


## 25. PIPELINE-JOIN-CARDINALITY-BATCH3：右表关联基数风险诊断（2026-10-16）

保留原关联键检查接口，新增右表行数、关联键组数、最大单键记录数，保留重复键组/额外行统计与 `exactJoinRowCount=false`。编辑器诊断绑定 JOIN 源/方式/稳定字段对，修改配置或异步过期返回不能展示旧诊断；仍要求显式确认行数放大风险，并明确该诊断不是 JOIN 最终行数。专项 H2、Node、TypeScript、Chromium 验收详见 [PIPELINE_JOIN_CARDINALITY_BATCH3.md](PIPELINE_JOIN_CARDINALITY_BATCH3.md)。未部署 18110，未连真实 StarRocks。


## 26. P1-MODEL-RELATIONS-CATALOG-01：已保存数据关系目录（2026-10-16）

公共数据新增「模型关系」入口，读取 `insight_dataset_pipeline` 中已保存的 JOIN/UNION 关系，展示关联数据集、字段、版本及 ACTIVE/STALE/INVALID 状态，能够返回现有加工编辑器。复用服务器既有安全 Planner/字段目录，不生成 SQL、不访问真实业务数据源、不创建新元表。见 [P1_MODEL_RELATIONS_CATALOG_01.md](P1_MODEL_RELATIONS_CATALOG_01.md)。本批只是可视化关系目录，不能标记任意多表语义关系编辑已完成；现有 18130 预览服务和 18110 线上服务均未覆盖。
