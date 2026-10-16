# 数据加工编辑增强第 4 批：中间步骤依赖影响检查（2026-10-16）

## 范围和真实行为

在已有安全 CAST、JOIN 预检、步骤复制、移动、撤销与真实服务端预览之上，增加**编辑器侧的逐步字段依赖检查**，直接修复中间步骤删除/移动后下游组件显示不出问题、尝试保存才收到后端错误的体验。

- 新增 `frontend/src/analysis/pipelineDependencies.ts`。从服务器目录获得稳定 base fieldId，按步骤顺序更新可用字段：计算列/条件列/类型转换生成新 fieldId；JOIN 输出指定新 fieldId；UNION 保持左侧字段；GROUP 替换为分组维度与汇总指标 ID。后续过滤、条件、转换、公式、GROUP 都基于**当前步骤之前**的字段检查依赖。
- 公式字段引用只跟踪合法 `[stableId]` 标记；对单引号字符串常量中的 `[文本]` 不当作引用。函数解析、SQL 语法、算子及真实数据类型由 `FormulaCompiler` / `AnalysisPipelineCompiler` 最终校验，前端不执行 SQL。
- 检查缺失字段、重复/非法输出 ID、SUM/AVG 对非数值字段、错误的 CAST 输入类型、GROUP 丢弃明细字段导致的下游依赖失效，以及 JOIN/UNION 被移动至非第一步。
- 显示顶部「N 处字段依赖错误」及每个失败步骤的明确原因；存在问题时禁止整链 SQL 预览、整链数据预览、保存；允许对**错误步骤之前的有效前缀**执行中间步骤预览。仍可通过已有撤销恢复步骤、编辑字段或重新排序来修正。
- 不自行删除或改写失败步骤、不静默回滚用户输入。错误草稿保留本地，后端保存/发布始终受原有合法性验证保护。没有变更 HTTP API、元数据库表、处理版本或 V1 报表。

## 定向测试
- `node --experimental-strip-types --test scripts/pipeline_dependencies.test.mjs scripts/pipeline_editor_step.test.mjs scripts/pipeline_join_preflight.test.mjs`：至少四步公式→过滤→汇总→过滤链、删除源字段、挪动 GROUP、引用单引号文本、JOIN/CAST 失效、输入对象不变。
- `cd frontend && npm run build`：Vue3 TypeScript / Vite。
- `scripts/pipeline_editor_browser_smoke.cjs` 在原模拟 REST 编辑器浏览器用例中增加：删除上游计算列后，下游两处引用错误在画面显示、禁用保存/整链预览、有效前缀仍可预览、撤销恢复。其他 CAST/JOIN 用例继续保留。
- CI：`.github/workflows/pipeline-editor-scoped.yml`，只在相关路径变更时运行本批及继承的专项检查。

## 兼容边界与下一步

该检查是一层客户端提示，不能替代后端对 `FormulaCompiler`、字段权限、JOIN 源绑定、真实类型和 SQL 语义的检查。非字面式动态字段来源、聚合公式类型推断和外部数据集权限仍只能由服务端确认。对于输入中途无效的历史草稿会立即提示，但不自动修改持久内容。V2 主题级筛选组件和同源跨数据集映射仍是下一批。

本次开发机依旧离线，只有授权 GitHub 代码编辑与 GitHub Actions 的隔离定向测试可用；没有运行或部署 `18110` 生产服务，没有触碰生产 H2/MySQL/StarRocks。
