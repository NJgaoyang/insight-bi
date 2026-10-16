# P1-SEMANTIC-RELATION-DRAFT-01 · 多表语义关系草稿（2026-10-16）

## 范围
本批从既有“加工链单次 JOIN/UNION 关系目录”推进到**单独可编辑的语义关系草稿元数据**。管理员可在「公共数据 → 模型关系 → 语义关系草稿」新增、编辑、删除主/目标数据集的关系定义，配置最多三组 stable fieldId 字段对应，看到 DRAFT / INVALID 状态。新增配置不进入任何 SQL Planner、读者请求或已发布报表，避免将未审批关系当作查询授权。

## 后端契约
- 新表 `insight_semantic_relation(id, name, source_dataset_id, target_dataset_id, pairs_json, revision, updated_at)`；仅启动时 IF NOT EXISTS 增量建表，不调整现有数据集和仪表板表。数据集对增加唯一约束，防重复。草稿采用 1 起始修订和 SQL 条件更新/删除的乐观锁，旧版本请求不能覆盖新版本。
- API：管理员 `GET/POST /api/model/semantic-relations`，`PUT/DELETE /api/model/semantic-relations/{id}`。沿用现有全站管理员会话与 CSRF 校验。仅有元数据操作，未添加任意 SQL 执行或自由表达式参数。
- 服务端核验两端数据集存在且属于同一 `connection_id`，不允许自关联；关联字段必须由已有 `FieldCatalog` 提供且非计算列、非隐藏，双方物理字段类型严格相同且已知。关联键为 1～3 组，左右字段不可重复；最多 12 条单主数据集草稿，全局列表最多 100 条。关联数据集或字段删除、类型变化后，列表显示 INVALID，不能按旧配置保存。
- 语义草稿只用于设计参考，**不改变**既有加工链 JOIN 风险确认、字段权限、数据发布快照、行级策略或加工修订。尚未实现多表可执行关系编译、数据建模审批或跨库 JOIN。本批没有使用 StarRocks 生产库执行真实数据查询。

## 定向测试
- Java/H2：`SemanticRelationApiTest,ModelRelationsApiTest` 检查新增/重复关系、类型不匹配、跨连接、自关联、非法或重复字段、版本冲突、删除和目标数据集消失后的失效状态。
- Vue/Vite：`npm run build` 检查独立草稿编辑器集成。
- Chromium：`semantic_relation_drafts_browser_smoke.cjs` 覆盖公共数据模型关系入口、同连接的数据集与字段选择、新建、编辑 CAS、删除。
- GitHub CI：`.github/workflows/semantic-relation-scoped.yml`。只测试修改范围，不全量回归。

## 风险与后续
多数据集关系草稿可能组成图，但它们尚不用于查询；在没有完成关系方向、扇出基数限制、版本批准、权限逐表叠加和有界 SQL Planner 之前，禁止让读者或普通查询执行这些关系。后续实现关系审核及模型版本提交门禁，再分批实现可执行多表语义查询。开发机原有 18130 预览和 18110 服务不重启、不替换。
