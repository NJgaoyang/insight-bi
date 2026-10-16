# P1-MODEL-RELATIONS-IMPACT-02 · 加工模型草稿/发布版本依赖影响

## 交付内容
在已保存 JOIN/UNION 关系目录增加「影响分析」，通过只读 REST `GET /api/model/relations/{datasetId}/impact` 统计数据集加工模型被分析主题引用的情况。与现有管理员权限保持一致，不新增用户权限、SQL 执行引擎或模型存储表。

- 后端仅检查现有 `insight_dataset_pipeline.revision` 和 `insight_dashboard.content_json/published_json`；只计入 V2 组件明确声明 `usePipeline=true` 且 `datasetId` 等于目标模型的数据。一般数据集引用、V1 报表、其他数据集不计入，避免影响分析误报。
- 将每个分析主题按 DRAFT 草稿和 PUBLISHED 已发布快照拆分展示，分别返回 `widgetCount`、`matchingRevision`、`outdatedRevision`。未保存加工版本时所有受影响绑定都视为需要更新，不因旧报表发布而忽略。
- 禁止客户端提交原始 SQL / 字段映射 / 查询条件；服务端不打开业务数据源、不执行用户查询、不修改数据库。限制单次扫描 200 个仪表板、每个快照 150 个组件、每段 JSON 300 KB，以避免无界内存和耗时。无效历史内容只读时跳过，结果不能替代后端发布门禁。
- 前端从全局「公共数据 → 模型关系」的关系卡打开影响分析，显示绑定的草稿/已发布主题，以及版本过期数。不提供任何删除/自动迁移的危险操作。模型目录与加工编辑器原入口继续有效。
- 仅检查**直接绑定**关系加工数据集的 V2 Widget，不声明已经覆盖跨多个报表的任意传递血缘或 SQL 字段级依赖。

## 定向验收
`ModelRelationImpactTest` 在 Mockito 隔离检查已发布与草稿分开、版本一致/过期、未绑定/其他数据集跳过、未知数据集拒绝、SQL 及数据源连接不执行。原 `ModelRelationsApiTest` 和 `AnalysisPipelineJoinTest` 继续运行；前端 `npm run build`，Chromium 模拟 REST 在关系目录内打开影响分析并检查草稿/已发布列表。
专项 CI：`.github/workflows/model-relations-scoped.yml`。未运行全量回归、未接真实 StarRocks；现有 `18130` 独立预览实例不更新，旧 `18110` 服务不重启。
