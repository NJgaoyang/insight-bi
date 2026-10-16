# 数据加工第二阶段第三批：JOIN 关联风险与配置校验（2026-10-16）

## 交付与原因

当前后端 JOIN 编译器已严格限制同一数据连接、第一步执行、INNER/LEFT、1–4 对等值关联键、1–30 个投影字段、类型兼容、重复字段和主动确认 `acknowledgeFanout`。本批避免重写后端已存在的安全逻辑，只补齐前端的即时反馈和确认失效规则。

1. 当用户**增加、删除或修改 JOIN 关联键，或切换 LEFT/INNER 关联方式**时，强制将 `acknowledgeFanout` 置为 false。旧确认不能自动套用到另一组键，避免合法修改后将新风险误认为已确认。
2. 前端针对本步骤所属的同连接数据集及实际字段目录检查：键组数量、字段 ID 存在性、类型是否一致、键组重复、投影数量及输出 stable ID/名称。发现无效配置或未确认行数风险时在 JOIN 步骤显示原因、禁止点击保存。
3. 校验仅作即时提示，**不能代替后端**：`AnalysisPipelineCompiler.join` 仍基于服务器字段目录、同连接数据集和真正 `acknowledgeFanout` 决定能否执行；`POST /pipeline/join-key-profile` 的重复键检查功能保持不变。任何客户端伪造请求仍需走服务器权限及编译检查。
4. 编辑器保存、预览、撤销/重做、revision 与加工版本的原有契约不变；旧有联接步骤及旧 V1 报表不迁移。若历史草稿包含无效关联字段，页面明确报错，修复前不允许保存。

## 定向验收

`node --experimental-strip-types --test scripts/pipeline_join_preflight.test.mjs` 检查有效/无效字段映射、重复键、不同连接、输出 ID 冲突和强制重新确认。隔离 Chromium `scripts/finebi_pipeline_join_ui_smoke.cjs` 验证新建 JOIN、选取键、确认、**增加再删键后旧确认不可复用**、重新确认后保存及重载。该脚本与前两批步骤复制/类型转换专项在 `.github/workflows/pipeline-editor-scoped.yml` 内运行，前端通过 TypeScript/Vite 编译。既有后端 JOIN JUnit 未被覆盖或删除。

由于授权开发机当前离线，无法连接真实 MySQL/StarRocks 验证实际数据源 fanout；只在 GitHub CI 中进行隔离单元及浏览器测试。未部署线上 18110，不更改元数据、不影响生产数据。

## 后续待完成

JOIN 多级依赖影响链可视化、步骤中途类型转换对后续配置失效的完整提前检测、V2 分析主题筛选组件嵌入与跨数据集显式映射仍待开发。
