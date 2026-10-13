# FineBI 自助分析计算引擎 P0 — 开发记录与交付说明

更新：2026-10-13；分支：`feat/finebi-analysis-engine-p0`。**本文件记录真实实现进度，不代表整个 P0 已验收完成。**

## 总体状态

| 任务 | 当前状态 | 已提交代码 | 未完成的验收 |
|---|---|---|---|
| P0-00 | **部分完成** | GitHub 分支建立，旧代码/API 检查完成 | 由于实行“GitHub 开发、自行拉取部署”，生产 MySQL/JAR 备份和独立预览环境由部署者操作；本次未操作远程服务器 |
| P0-01 | **核心代码完成，迁移待线上验证** | `FieldCatalog`、MySQL `insight_dataset_field` 和 `insight_calculated_field` 两张增量表、`GET /api/datasets/{id}/fields` | 真实 StarRocks JDBC 类型元数据与旧数据持久化验收、字段重命名引用迁移 |
| P0-02 | **核心代码完成，SQL 只读校验仍需增强** | `AnalysisQueryPlanner`、`POST /api/analysis/v2/compile`、`.../query`；最多 3 维度/5 指标；分组、日期粒度、排序、结果上限 | 完整 SQL AST / 自定义 SQL 数据集白名单验证；查询取消、并发限制 |
| P0-03 | **基础公式与前后过滤完成，高级语义待完善** | `FormulaCompiler`、行级/聚合阶段、WHERE/HAVING、参数绑定、公式新增/修改/删除/验证 | 完整计算字段类型推断、循环引用和表达式依赖管理的复杂场景；CASE/日期/窗口/同环比按后续批次 |
| P0-04 | **基础 UI 已接入，浏览器联调待执行** | 新组件 V2 字段列表/多槽拖拽、计算字段输入、过滤、排序、图表渲染与保存；原 V1 组件保留 | 真实浏览器多字段拖拽测试、撤销/重做、饼图与多指标互斥提示优化、更多图形属性 |
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
6. GitHub Actions 持续运行 `BiQueryInTest`、`FineBiWorkspaceFolderTest`、`AnalysisQueryPlannerTest`、`AnalysisEngineSqlExecutionTest` 及 Vue 的 TypeScript + Vite 构建。
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

## 后续优先事项

1. 严格 SQL AST 只读校验和数据库最小权限（阻塞生产级安全验收）。
2. 真实 StarRocks E2E 与慢查询/参数边界验证。
3. 计算字段依赖、更多函数和精度规则；字段变更时的引用迁移。
4. 组件分析 V2 浏览器拖拽、筛选、保存重开测试和撤销/重做。
5. 完成任务书 P0-05 后再把开发分支合并到主分支。
