# 数据加工第二阶段第二批：受控类型转换（2026-10-16）

## 交付内容

在已上线代码分支的步骤前后移、过滤、计算列、条件列、同连接 JOIN/UNION 与分组汇总之上，新增 **cast 类型转换步骤**，不新增另一套加工引擎或数据源执行入口。

- 后端 `AnalysisPipelineCompiler` 增加 `type:'cast'`，请求需提供 `id`、新输出的稳定 `fieldId`、`sourceFieldId`、`name`、`outputType`。字段必须源自已获授权数据集或前置合法步骤，不能提交自由 SQL、CAST 片段或表列名。编译器固定生成安全引用的 SQL `CAST`，原字段继续保留；后续过滤及聚合可以按新稳定 ID 引用输出。
- 为避免 MySQL/StarRocks 的宽松数值强制转换将不合法字符串静默转为 0，本批**明确禁止 STRING → DECIMAL**。支持 INTEGER/DECIMAL/DOUBLE → DECIMAL(38,12)，以及 INTEGER/DECIMAL/DOUBLE/DATE/DATETIME/BOOLEAN → CHAR(255)（输出逻辑类型为 STRING）。字符串和不支持的类型会明确报错。
- `DECIMAL(38,12)` 可能按比例舍入，过大整数可能溢出；对 CHAR(255) 的目标长度也有限制。本批提供限制提示，不承诺无损、自动清洗或多数据源的完全等价 CAST 表现。转换仅作用于查询输出，不写业务表。
- 前端 `PipelineEditor.vue` 添加新步骤入口、合法来源选择、只允许的目标类型、保存与加载；编辑历史、预览到任意步骤、步骤排序等继续使用前一批接口和流程。为避免源字段覆盖，新类型转换永远建立新的输出字段。
- 本次不改 `AnalysisPipelineApi` 的编译/预览/保存端点。它使用既有服务器端校验、只读 JDBC 查询、修订号及版本固定机制。旧流程和保存 JSON 不迁移、不改义。

## 定向验收

`cd backend && mvn -B -q -Dtest=AnalysisPipelineCastTest,AnalysisPipelineCompilerTest test`：H2 MySQL 模式执行固定 CAST、转换后过滤/分组、稳定字段 ID、原始字段保留以及无效值/注入拒绝。前端 `npm run build`，Chromium 的 `scripts/pipeline_cast_browser_smoke.cjs` 验证增步骤、预览请求、保存和刷新；原有步骤复制/撤销/重做专属用例仍执行。专项 CI 只对修改的路径触发。

由于本次远程开发机离线，无法执行真实 StarRocks/MySQL JDBC 验证；其语法需要在专用数据源环境补验，当前定向 H2 + CI 不能代替真实驱动验收。生产 `18110` 服务、元数据和业务库均未部署或修改。不能把这批等同于任意类型转换或清洗规则已经完成。

## 下一阶段

下批优先补复杂 JOIN 影响分析与依赖校验（尤其移动步骤后字段失效提示）、编辑器修改影响链和主题过滤组件。保持稳定字段/权限/版本合同。
