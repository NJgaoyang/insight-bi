# P1-SEMANTIC-DASHBOARD-DRAFT-BINDING-08：语义候选绑定分析主题草稿与发布门禁

2026-10-17；开发分支：feat/finebi-analysis-engine-p0。

## 交付

分析主题草稿的 V2 组件增加可选 analysisV2.semanticRelation 对象，仅接受 relationId、readerId、releaseNo 三项。保存时服务器按单一版本号精确查询不可变候选；重新验证快照 SHA-256、已确认关系修订、源 SQL/物理字段、受控输出及读者权限指纹；组件 datasetId 必须属于该关系左表，不能同时启用 usePipeline。此字段仅存储草稿元数据，不能形成可执行 JOIN。

正式 POST /api/dashboards/{id}/publish 遇任何语义候选绑定均明确拒绝（包括已通过审批的分析主题），普通主题原发布逻辑不变。ReaderColumnGuard 对被污染的已发布快照也拒绝 catalog/report/query；AnalysisApi 拒绝携带 semanticRelation 的 compile/query，避免静默退化成单表查询。旧版本恢复若带候选字段，也不能发布。

## 测试和限制

H2 专项覆盖精确版本、读者与左侧数据集归属、权限或源变化失效、存草稿但不可发布、Reader guard 对伪造已发布 JSON 的拒绝，以及不带绑定的普通发布。无新 DDL、不接入 ReaderApi 执行、不运行真实 JOIN、未部署 18110/18130。

完整双侧行级权限下推和关联 SQL 运行时权限检查必须先完成，才能考虑后续正式模型发布。

## 管理员绑定 UI

关系审查对话框展示已封存候选（SEALED_ONLY）的“绑定分析主题草稿”操作，可选择 kind=analysis 的目标主题、其主数据集与当前关系左侧一致且未启用加工源的 V2 组件。前端根据服务端 dashboard revision 执行 CAS PUT，并保留已有组件/视图配置；后端重复校验不可变候选版本及来源/权限条件。候选组件在 AnalysisV2Chart 内明确显示不可执行提示，不会退化查询为原始单表 SQL。Chromium smoke 覆盖封存→选择主题/组件→保存草稿绑定。
