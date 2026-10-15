# ANALYSIS-PHASE2-01 · V2 分组/交叉表第一批（2026-10-15）

## 范围与依赖

以 `docs/INSIGHT_BI_DEVELOPMENT_HANDOFF_20261015.md` 第 7 节为验收基线，保留 V1 Designer/ChartWidget pivot 的旧结构，**新增 V2 交叉表入口**并复用现有 `AnalysisQueryPlanner` 的参数化 SQL 聚合与安全行数预算。

本批仅支持 **恰好 2 个维度（第 1 个为行，第 2 个为列）及 1–5 个指标**；字段槽的拖动/交换即行列交换，前端提供行/列升降序及合计开关。字段均沿用已有 stable fieldId、原聚合方式与权限过滤，不额外提交 SQL。原 V2 3 维度上限对其他类型保持不变；交叉表第一批只使用 2 维度。

## 查询与展示合同

- 后端使用原 V2 `GROUP BY` / 只读 SQL / 15 秒约束 / 4 并发限流，保留最多 500 组及一行 sentinel 的真实 `truncated` 标记。没有新增 SQL 执行端点。
- 查询列的 metadata 新增可选 `additive:boolean`：普通 `SUM`、非 DISTINCT 的 `COUNT`、`COUNT(*)` 为 true。AVG、MIN、MAX、COUNT DISTINCT 和聚合公式等均为 false。原查询列 `id/name/type/kind/label` 不变，旧客户端可忽略新字段。
- 前端仅把**后端已分组聚合**的结果透视，按 `result.columns[].id` 定位维度与指标，按 `columns[].name` 读取数据。列展示名称重复不影响绑定，NULL 与文字“(空值)”使用不同分组键。缺字段、重复组、无效配置直接报错，不用浏览器拼 SQL 或二次聚合业务明细。
- 只有 `additive=true` **且查询未截断**时才按各分组聚合结果计算行合计、列合计、总计；其它全部显示 `—`。十进制累加使用字符串/BigInt 防止常见浮点求和误差；无数值的 NULL 组不充作 0，不支持安全解析的异常数据亦不展示伪合计。**这不是 SQL `ROLLUP` 完整小计**。
- 最多渲染 100 个行分组、24 个列分组，每页显示 20 个行分组；超限要求用户筛选，不静默截断。合计基于完整的当前查询结果（非单页）；如果 V2 的 `truncated=true` 则整个合计列全部禁用并警告。
- 组件仍保存 `type:'pivot'`、原 `analysisV2.dimensions/measures`，新增可选 `analysisV2.pivot` 仅用于显示排序/开关。已发布组件由服务器发布快照查询；Viewer 仅能查询获授权的已发布快照，目录/行列 ACL 与快照版本链路不变。

## 定向验收

- `cd backend && mvn -q -o -Dtest=AnalysisPivotGroupedQueryTest,AnalysisQueryPlannerTest test`：H2 MySQL 兼容模式，验证真实 SQL 多维分组、NULL、DECIMAL、多指标、COUNT DISTINCT、stable fieldId、非法字段拒绝、sentinel 限流和 `additive` 判定。
- `node --experimental-strip-types --test scripts/analysis_phase2_pivot.test.mjs`：测试二维交叉、字段标签重名、NULL、浮点十进制安全求和、排序、非法结果、截断/超限 fail closed。
- `cd frontend && npm run build`：Vue TypeScript 检查与 Vite 构建（仅隔离工作目录）。
- `P0_PREVIEW_URL=http://127.0.0.1:18129 node scripts/analysis_phase2_pivot_browser_smoke.cjs`：隔离静态前端 + mocked REST，在真实 Chromium 验证组件切换、行列×多指标、合计、编辑保存、重进、双视图、发布快照、截断禁用及分页；不访问生产。
- GitHub 路径触发的独立 CI：`.github/workflows/analysis-phase2-pivot-scoped.yml`。它只运行上述修改范围的检查；**CI 成功必须等待远端实际运行证据，不能提前宣称通过**。

## 兼容/风险与下一批

未触碰既有 V1 JSON、旧 Designer、用户授权/审批逻辑、业务数据库表、线上运行服务或元数据迁移。

仍未完成 SQL ROLLUP 精确全局 AVG/COUNT DISTINCT、3 维度及多级小计、自定义行列层级、条件格式、自动列虚拟化、Excel/PDF 高级导出。当前前端聚合展示依赖 JSON 数值精度；遇到超出 JS 安全数值范围或指数格式的小数合计会保守拒绝，但单元格本身仍遵循既有 V2 展示逻辑。若需大额高精度金融展示，下一批需引入**受兼容约束的 Decimal 字符串传输和服务端聚合小计**。

此批**没有部署到在用 `18110` 服务**；只在隔离目录 `/opt/insight-bi-phase2` 开发和测试，原目录 `/opt/insight-bi` 的其他工作区文件不覆盖。
