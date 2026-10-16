# PIPELINE-RELATION-IMPACT-BATCH2：JOIN 字段引用影响链

以已有关系可视化和步骤 stable fieldId 为输入，增加右表输出字段的顺序引用追踪：直接使用、公式计算输出、条件列、类型转换、分组汇总指标以及之后的明确字段引用。GROUP 之后只有保留的维度和汇总指标继续传播，不再错误地把原明细字段当作可用。

面板逐个右表输出字段展示关联的下游步骤顺序、类型和无引用状态。计算依据仅是当前编辑的步骤 JSON，不修改 SQL、数据库、权限、审计或处理版本。追踪属于可解释编辑辅助，不代表全部隐式行级影响，后端 SQL 编译器仍是合法性最终裁决。

定向测试：scripts/pipeline_relation_graph.test.mjs 检验直接与多级传递、公式字符串字面量不误判、GROUP 字段收缩。scripts/finebi_pipeline_join_ui_smoke.cjs 检查界面和输出项变化，npm run build 检查 Vue/TS。未连接生产源，未部署 18110。
