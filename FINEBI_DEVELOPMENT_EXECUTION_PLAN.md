# Insight BI · FineBI 风格自助分析系统实施总计划

> **执行入口 / 开发任务基线** · 2026-10-13
> 当前代码：`/opt/insight-bi` · 基线分支 `feat/finebi6-workspace-20261013` · 基线提交 `8ac5a03`
> **下一份必读文件：[`docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md`](docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md)**（P0 按任务/接口/测试的执行细则）

## 0. 一句话目标与范围

打造**交互方式参考 FineBI 6.0、完全独立实现**的 StarRocks 直连型企业 BI。用户从「我的分析 → 分析主题 → 选择已有数据集 → 拖拽维度/指标 → 公式与过滤 → 图表/表格 → 仪表板 → 保存/发布」完成自助分析，普通使用者不需要手写 SQL。

- **前端**：Vue 3 + TypeScript + Element Plus + ECharts，紧凑中文企业产品，不新增大面积宣传卡片、介绍文字或虚假按钮。
- **后端**：Java 21 + Spring Boot 单体应用，自己实现字段模型、BI 计算语义、结构化查询、StarRocks SQL 编译；StarRocks 经 JDBC 真正执行数据处理。
- **元数据**：MySQL 存数据集、字段公式、主题、组件、仪表板、权限、版本；不把业务明细复制进 MySQL。
- **保留**：原 `/api/query`、现有数据集/仪表板/发布快照以及历史配置的读取能力；新接口独立增量接入，新模型采用 `schemaVersion`。
- **排除**：抽取、ETL、SeaTunnel、定时更新业务明细、嵌入/反编译 FineBI 专有 JAR、复制商用源码与 UI 素材；Datart 历史迁移程序独立另做。
- **首期只做 P0**：计算语义 + 安全 SQL 编译 + 多维/多指标分析 + 保存/验收。**不为了赶界面而绕过后端真实计算**。

## 1. 现有代码真实状态（后续开发前再次确认）

| 模块 | 已有能力 | 本计划必须补齐 |
|---|---|---|
| `frontend/src/AnalysisSubject.vue` | 选择已有数据集、单维度/单指标、基础图表类型、保存和发布、组件预览 | 多字段槽、多指标、计算字段编辑、过滤阶段、查询错误与公式反馈 |
| `frontend/src/ChartWidget.vue` | ECharts/表格渲染、部分排序下钻/透视/筛选 | 统一多系列数据协议、按指标图形属性、日期粒度及完整表格场景 |
| `frontend/src/Designer.vue` | 单独的 GridStack / Moveable 仪表板编辑器 | 将组件分析成果无损复用到主题内仪表板（后续 P1） |
| `backend/.../BiApi.java` | `POST /api/query` 可查一个数据集并做 SELECT、分组/过滤/排序 | V2 多维多指标、表达式编译、WHERE/HAVING/窗口阶段，不在 Controller 拼 SQL |
| `backend/.../DatabaseStore.java` | MySQL 元数据、StarRocks JDBC、字段基本信息、正则形式 SELECT 限制 | 稳定字段 ID/类型、受控 SQL AST、计算字段元数据、依赖关系和审计 |
| `frontend/src/api.ts` | `Field {name,type,numeric}`、单维 `Widget` | 保持旧接口兼容，新增 V2 Query/Field/Expression 类型 |

当前已有文件夹/分析主题、StarRocks 连接、数据集、旧设计器和发布版本，**不可误判为空项目或推倒重做**。当前 `safeSql` 仅是字符串规则，并非完整 SQL 解析/安全验证；本计划要在新的分析编译器中建立更严格的验证。

## 2. 统一架构与计算顺序

```text
FineBI 风格分析主题 / 数据集字段
   ↓ 构造结构化 QueryRequest（字段稳定 ID、计算公式 AST，不提交任意 SQL 片段）
FieldCatalog + FormulaParser + TypeChecker + BI QueryPlanner
   ↓ 明细公式 → 明细 WHERE → GROUP BY / 聚合 → 聚合公式
   ↓ HAVING / 结果过滤 → 动态/窗口计算 → 排序 / TopN / LIMIT
StarRocksSqlCompiler（字段/函数白名单，值参数绑定）
   ↓ 可审计 SQL + params + 查询执行计划
JDBC → StarRocks（只读权限、超时、最大结果条数）
   ↓ 标准行列数据 + 指标格式 + 执行摘要
ChartWidget / 表格 / 仪表板
```

**计算语义不可妥协**：`SUM(profit) / NULLIF(SUM(revenue),0)` 和先逐行算利润率再 AVG 不是一回事；`WHERE` 与 `HAVING` 也不是一回事。查询规划器必须明确 `ROW`、`AGGREGATE`、`POST_AGGREGATE`、`WINDOW` 四个计算阶段。禁止把前端任意公式直接拼入 SQL。

建议第一阶段采用**自有结构化 AST + StarRocks 方言编译器**；先评估 SQL 解析库对只读验证的支持，Calcite 可在后续复杂查询规划有确切需求时引入。无需为了“DataEase 用 Calcite”而重构整个服务。

## 3. 执行顺序与交付门禁

**每一个任务都必须满足：代码/API 可运行、正确 SQL、元数据可恢复、边界测试、浏览器测试（如有 UI）五项要求。** 未满足不可标注完成，不能只提交截图。

| 序号 | 阶段 / 目标 | 依赖 | 可交付成果 / 门禁 |
|---|---|---|---|
| **P0-00** | 基线冻结与测试环境 | 无 | 新分支、现有端点契约清单、MySQL 备份、测试数据集方案、旧报表回归基线 |
| **P0-01** | 统一字段模型 | P0-00 | 稳定字段 ID、类型推导、数值/日期、物理/计算字段元数据，旧 `fields_json` 无损读取 |
| **P0-02** | 查询 V2 协议与结构化 Planner | P0-01 | 多维多指标分组、排序/TopN/限量、SQL 预览、旧查询接口继续可用 |
| **P0-03** | 前后过滤和表达式计算 | P0-02 | 参数化 `WHERE`、聚合 `HAVING`、行级及聚合级公式、NULL/除零正确 |
| **P0-04** | FineBI 式组件分析器 | P0-02/03 | 拖拽多个维度/指标，聚合/公式、图表系列、出错展示、保存重新打开恢复 |
| **P0-05** | StarRocks 真实验收和交付 | P0-01～04 | 样例收入/成本/利润对账、异常/注入拒绝、旧页面回归、回滚方案、测试报告 |
| **P1-01** | 主题统一资源模型 | P0 | 一个主题多数据集、多组件、多仪表板，版本及引用依赖 |
| **P1-02** | 数据编辑步骤引擎 | P1-01 | 公式列、过滤、条件列、分组汇总、同源 JOIN/UNION、步骤顺序与预览 |
| **P1-03** | 仪表板编排统一 | P1-01/02 | 将现有画布设计器嵌入主题；联动/下钻/筛选/跳转、发布快照 |
| **P2-01** | 高级分析与图表属性 | P1 | 占比、排名、累计、同比环比、复杂表格、多指标独有样式与导出 |
| **P2-02** | 企业权限与发布治理 | P1 | 用户/部门/角色、行列/资源权限、目录可见性、发布审批、审计 |
| **P3** | 可靠性与独立迁移工具 | P2 | 查询并发/取消/慢查询、运行治理；另建不侵入 BI 核心的迁移程序 |

**本次立即执行范围：P0-00 → P0-05；P1/P2/P3 暂不启动。** P0 的文件级任务、接口 JSON、SQL 样例、验收矩阵见 `docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md`。

## 4. P0 的验收任务（不可用“页面有按钮”代替）

- [ ] 不写 SQL：在分析主题中选择 StarRocks 数据集，拖入「城市、月份」两个维度及「收入、成本、利润」三个指标，查询结果、分组键与图表系列正确。
- [ ] 创建行级字段 `profit = revenue - cost`，表达式类型错误能被保存前拒绝。
- [ ] 创建聚合指标 `SUM(profit) / NULLIF(SUM(revenue),0)`，分组后得到正确利润率；零收入与 NULL 无异常。
- [ ] 同一数据比较明细过滤 `revenue >= 150` 和聚合过滤 `SUM(revenue) >= 150`，能解释结果差异。
- [ ] 支持各指标独立聚合方式、别名、数字格式和排序/TopN；不依赖图表前端计算聚合。
- [ ] 查询编译接口只生成/返回受控查询计划；SQL 注入、不存在的字段、未知函数、跨数据源引用、类型不兼容操作被拒绝。
- [ ] 保存、刷新、重进同一主题，字段、公式、维度、指标、类型及样式完整恢复；旧主题/旧仪表板原样可打开。
- [ ] 真实 StarRocks 查询结果与明确的手工 SQL 基准比对（包括日期、NULL、零、重复数据）；只读账号/超时/行数上限实际生效。
- [ ] 仅运行修改模块相关单元、集成和浏览器测试，测试日志及尚未完成项写入交接文档。

## 5. 推荐的仓库组织（新建包/文件，不必移动全部旧文件）

```text
/opt/insight-bi/
├── FINEBI_DEVELOPMENT_EXECUTION_PLAN.md    # 本文件，总计划/门禁
├── docs/
│   ├── FINEBI_ANALYSIS_ENGINE_P0_TASKS.md   # 下一步按任务执行
│   ├── FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md  # 官方能力对照
│   └── FINEBI6_WORKSPACE_HANDOFF_20261013.md     # 已实现功能基线
├── backend/src/main/java/com/insightbi/
│   ├── BiApi.java / DatabaseStore.java       # 原兼容接口
│   └── analysis/                             # P0 新建：model, formula, planner, sql, service
├── backend/src/test/java/com/insightbi/analysis/  # P0 定向测试
├── frontend/src/
│   ├── AnalysisSubject.vue / ChartWidget.vue # 原组件增量改造
│   ├── api.ts                                # 兼容 V1 + 新增 V2 类型
│   └── analysis/                             # P0 新增字段槽、公式面板与状态模型
└── scripts/                                  # 浏览器/ API / 真数对账定向脚本
```

## 6. 首次执行前的严格步骤

1. **确认基线**（不要直接在运行中的 FineBI 目录操作）：
   ```bash
   cd /opt/insight-bi
   git status --short
   git branch --show-current
   git log -1 --oneline
   cat docs/FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md
   cat docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md
   ```
2. **新开发分支**：只在工作区干净且确认当前提交之后执行 `git switch -c feat/finebi-analysis-engine-p0`；已有同名分支则切换，不覆盖同名分支的既有改动。
3. **冻结原功能**：记录 `GET /api/health`、旧 `/api/query` 请求/响应契约、元数据库表结构和当前已发布快照；先执行现有针对性测试，确认基线有效。
4. **备份**：确认 `runtime/mysql.env` 仅服务器权限 600；从环境文件安全读取连接参数备份 `insight_` 前缀元数据表到 `runtime/`（600 权限）；禁止把数据库口令、SQL 参数实际值提交到 Git；任何不可逆 DDL 先单独审批。
5. **隔离运行**：本地/独立预览端口先使用 `ss -lntp` 检查空闲端口；生产 `18110`、本机 FineBI `37799`、DataForge `8080`、Datart `8087`、旧编辑器 `18112` 不得无故停机。
6. **增量开发**：按 P0-01～05 顺序，一任务一组代码/定向测试/提交；避免一次性重写 `BiApi.java` 与整个页面。
7. **合并/部署条件**：P0-05 未通过时不把半成品暴露给现有用户；正式部署前检查备份、新旧元数据和回滚路径，部署后只验证相关功能和既有旧看板入口。

### 现有可复用的定向命令

```bash
cd /opt/insight-bi
(cd backend && mvn -q -Dtest=FineBiWorkspaceFolderTest,BiQueryInTest test)
(cd frontend && npm run build)
node scripts/finebi_workspace_ui_smoke.cjs
node scripts/finebi_analysis_ui_smoke.cjs
node scripts/finebi_public_data_ui_smoke.cjs
git diff --check
```

P0 新增模块的测试命令和模拟/真实数据测试分开执行，详见 P0 文档。**原有浏览器测试只证明模拟接口交互，不代表真实 StarRocks 查询已验收。**

## 7. 代码与数据变更规则

- **先读现状后修改**：确认路由和类确在使用；避免重复实现已有下钻、TopN、透视/多选筛选。
- **持续兼容旧自研数据**：旧查询 DTO 和旧 `Widget` JSON 在未迁移前仍能读取；`schemaVersion` 缺失视为 V1；新增模型提供明确 V1→V2 适配，不直接改掉所有旧数据。
- **MySQL 变更**：DDL 脚本可重复执行，有向前兼容和检查/回滚说明；新增字段不能更改已发布历史快照的意义。实际迁移前留备份。
- **安全边界**：数据库真实查询只读账号；后端核验字段 ID/公式/权限，过滤值参数化，禁止拼接自由 SQL；管理员自定义 SQL 数据集也须只读验证和限时执行。
- **验收优先**：真实执行所得行列数据要对账，前端 ECharts 只渲染，不重新计算利润率等业务聚合。
- **开发纪律**：不引入 FineBI 内部 JAR 或商用资源；默认不全量回归，只测变更涉及区域并提供失败/回滚记录；无内置虚假业务数据。
- **迭代记录**：每次结束更新任务复选框、尚未完成项、测试证据、数据库迁移、提交哈希与环境访问情况。若失败则保持复选框未勾选，不得用代码提交代替验收。

## 8. 每个阶段输出模板（提交 PR / 交给本地 AI）

```text
任务编号 / 目标：
现状验证（实际代码/路由/数据库）：
本次修改（文件、接口、SQL 语义）：
兼容与元数据迁移（是否涉及生产）：
定向测试命令及结果：
真实 StarRocks 数据对账证据（如适用）：
已知限制与后续待办：
分支 / commit：
是否可进入下一任务（是/否 + 原因）：
```

## 9. 官方参考和本地对照

- FineBI 6.X 文档：https://help.fanruan.com/finebi6.X/
- 官方模块核对表：`docs/FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md`（“详阅”与“索引”分开标注，不表示读完所有官方文档）
- 本机 FineBI 6.0：`/opt/FineBI6.0`，用于观察授权范围内交互，不修改、不引入内部包
- 之前完成情况：`docs/FINEBI6_WORKSPACE_HANDOFF_20261013.md`
- 数据架构：StarRocks 直连，**查询与计算语义由自研后端编译**；未来独立迁移程序另建仓库

**立即下一步：执行 `docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md` 的 P0-00；不得跳过安全与旧接口基线直接实现图表 UI。**
