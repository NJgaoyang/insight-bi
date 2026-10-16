# 数据加工编辑器第二阶段第一批：安全复制与中间步骤预览（2026-10-16）

## 本批交付
基于既有 `PipelineEditor.vue` 和 `POST /api/datasets/{id}/pipeline/preview`，不新增原始 SQL、后端存储表、数据源类型或历史 JSON 迁移。

- 在计算列、条件列、过滤步骤上新增「复制」；输出新步骤 ID，并为计算列/条件列分配新的稳定输出 fieldId。公式的已有字段引用和条件列 sourceFieldId 保留，避免破坏合法依赖。不允许直接复制 JOIN、UNION、GROUP 等复杂操作，防止重复连接导致字段冲突和数据膨胀。
- 新增每步「预览到此步」：仅提交从第一步到该步骤的结构化配置及已有 1–200 行 LIMIT 给服务端真实加工预览端点，不用浏览器执行 SQL；返回数据展示“截至第 N 步”，编译或执行失败由服务器返回错误。
- 编辑历史采用最多 30 个快照，仅本地撤销/重做草稿步骤，不额外提交后端；历史在加载和成功保存后清空。无效操作不会写入历史，变更/撤销会清空过期预览。
- 复制、移动、修改后的整条链仍由现有后端编译器校验 SQL、计算字段引用和同连接 JOIN/UNION 等约束，保存沿用 `revision` 的并发控制和已固定的加工版本。没有在生产业务数据上执行 DML、DDL。
- 修改范围为 `frontend/src/PipelineEditor.vue`、新增 `frontend/src/analysis/pipelineEditing.ts` 和两个定向测试脚本及专项 CI。

## 验收
`node --experimental-strip-types --test scripts/pipeline_editor_step.test.mjs` 检查复制字段稳定 ID、引用保留、禁止复杂 JOIN/UNION/GROUP 复制、有限步骤链预览不修改输入；`npm run build` 检查 Vue 3 / TypeScript 与 Vite；`scripts/pipeline_editor_browser_smoke.cjs` 在隔离 Chromium + mocked REST 环境下核对 3 步链的公式复制、中间步骤预览请求、撤销/重做、保存和重进。`.github/workflows/pipeline-editor-scoped.yml` 仅针对本批代码自动验收。

**环境限制**：远程开发机连接离线，代码通过授权 GitHub API 写入原分支，CI 成功后才视为该批完成；不能声称开发机/真实 StarRocks 通过。没有部署线上 18110。

## 后续
步骤类型转换、复杂 JOIN 配置预检查、可视化影响链、跨数据集语义模型和主题内筛选组件仍需分批实施；不要重写已有的步骤前后移动与后端加工查询服务。
