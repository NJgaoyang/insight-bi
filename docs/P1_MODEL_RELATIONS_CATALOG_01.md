# P1-MODEL-RELATIONS-CATALOG-01 · 已保存多表关系目录

## 目标与范围
从已经持久化并由后端安全编译器管理的 JOIN/UNION 加工步骤，生成全局、只读的模型关系目录。管理员在「公共数据 → 模型关系」查看源/目标数据集、关系模式、字段绑定、输出投影数、保存修订版本，并从关系目录直接打开现有的加工编辑器。

本批**不新增新的关系事实表、不执行源数据库 SQL、不复制原 JOIN 编译器，也不开放任意跨连接 SQL JOIN**。只显示每个已保存加工链所支持的首个同连接 JOIN/UNION 关系；不能当成无限多表语义模型。

## 数据与安全契约
- `GET /api/model/relations` 复用现有管理员 API 授权，不接受 SQL/字段筛选表达式等查询参数。扫描已持久化的 `insight_dataset_pipeline` 元数据，不访问真实 MySQL/StarRocks 数据源，也不修改源表。
- 服务端关联条件按 stable fieldId 从目录转换为管理员可读名称，仅返回关系名称、字段 ID/名称、状态和版本。绝不返回源 SQL、数据库账户或加密口令。
- 用原加工 API 的 `get` 判断 source SQL/关联源变化是否使保存版本过期；用现有 `AnalysisPipelineCompiler` 的 compile 方法校验现存配置。状态为 ACTIVE / STALE / INVALID；过期或无效关系允许查看，但不能被报告为有效。数据集删除、连接变化、字段失效均给出保守提示。
- 单次限制 500 个元数据数据集和 100 个已保存加工关系，只读页面在大目录下拒绝无界扫描，不做隐藏的后台任务、自动数据源连接或无界行扫描。
- 当前接口对字段目录进行已有的 FieldCatalog 标识同步（可能初始化必要的元数据 stable ID），但不执行底层业务 SQL，不改变加工配置及版本。

## 定向测试
- Java/H2 和 Mockito：`mvn -q -B -Dtest=ModelRelationsApiTest,AnalysisPipelineJoinTest test`。覆盖合法 JOIN 字段、版本号、响应不泄露 SQL、跨连接与过期状态、服务端编译错误隔离。
- Vue/Vite：`cd frontend && npm run build` 检查 App 集成与只读目录组件。
- 隔离 Chromium：`scripts/model_relations_browser_smoke.cjs` 模拟 REST 验证公共数据入口、有效/无效关系、字段映射、点击返回现有编辑器。CI 工作流 `.github/workflows/model-relations-scoped.yml` 只在本批相关文件改变时运行。
- 不运行全量回归。不部署生产 18110；2026-10-16 在用的 18130 预览实例保持不动。本批成功后可另行要求将此分支更新到 18130。

## 下一阶段
后续建立受控多表语义模型的新增/编辑与版本授权，而不是让现存仅一次 JOIN 的编译器直接接受无限链式 JOIN；应在保留字段权限、关系基数风险确认、数据集版本固定和依赖分析后逐批推进。
