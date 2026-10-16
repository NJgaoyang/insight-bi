# PIPELINE-JOIN-CARDINALITY-BATCH3 · 右表 JOIN 关联键基数与过期诊断（2026-10-16）

## 本批交付

补强已存在的 `POST /api/datasets/{id}/pipeline/join-key-profile`，不创建新 API、新表或第二套关系引擎。

后端：沿用实际 JOIN 编译器的 stable fieldId、同连接校验与合法 JOIN 键验证；在受限只读连接里执行一个右表分组 SQL，新增 `rightRows`（扫描右表行数）、`keyGroups`（右表不同关联键组数）、`maxRightMultiplicity`（单组最大记录数），保留旧的 `duplicateGroups`、`extraRightRows`、`hasDuplicateKeys`、`scope=RIGHT_SOURCE`、`exactJoinRowCount=false`。统计只在点击检查时发生，不引入后台自动查询；保留查询限流与 10 秒超时。0 行右表显示 0 而不是 NULL。禁止将这些数字显示成最终 JOIN 扩大倍数：左表键分布、NULL 等值连接语义和数据变更都未纳入结果估计。

前端：使用 `datasetId + 步骤 ID + 右表 ID + JOIN 方式 + 按序关联字段对` 生成稳定诊断指纹。诊断结果必须与当前 JOIN 配置一致才显示；修改键、切换源、撤销/重做、删除步骤和切换加工内容均使旧诊断失效。请求发出后若步骤变化或启动了更新请求，旧响应不允许覆盖新页面。仅勾选「我已了解行数放大风险」不改变关联键，诊断结果可以继续显示，但仍需要显式确认。对无效/不可信统计值采取拒绝显示的保守策略。

本批不是精确 JOIN 行数预估、列级索引建议、关联基数约束持久配置或多个关联的任意语义模型；也没有取消已有 JOIN 风险确认/权限校验。

## 定向验收

- Java / H2：`mvn -B -q -Dtest=AnalysisPipelineJoinTest,AnalysisPipelineCastTest,AnalysisPipelineCompilerTest test`，验证两键重复、单键基数、右表空数据、保留旧字段，以及既有只读安全编译测试。
- Node：`node --experimental-strip-types --test scripts/pipeline_join_cardinality.test.mjs` 与原加工编辑专项。校验指纹、更换关联键导致诊断失效、空表/唯一键和错误数据拒绝。
- Chromium：`finebi_pipeline_join_ui_smoke.cjs` 增补点击诊断、查看最大重复数、修改 JOIN 条件清除旧值、重新诊断、确认风险但保留当前检查结果，以及原有保存/重进。
- Vue TypeScript/Vite：`frontend/npm run build`；由 `.github/workflows/pipeline-editor-scoped.yml` 执行本批定向检查。只有 GitHub Actions 成功才能标记远端专项通过。

## 范围及后续

开发机当前离线；不部署线上 18110、不开真实 StarRocks 只读连接、不写业务表。本批反映的数量只是检查当时**右表**分布，不能保证将来数据库数据不变，也不应作为无需风险确认的证明。下一阶段优先可授权的语义模型关系目录、跨数据集字段影响查询和模型版本兼容门禁。
