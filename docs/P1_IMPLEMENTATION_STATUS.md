# Insight BI · P1 实施状态

> 2026-10-13 · P1-01 **增量开发中**（仅实现主题资源图和共享组件多视图，不代表 P1-01 全部完成）。
> 使用分支 `feat/finebi-analysis-engine-p0`；生产 `18110` 没有部署本次功能。

## 执行安排

根据使用者指示，**DataForge StarRocks 真实对账暂缓**，继续执行不依赖该连接的后续开发。原 P0-05、G4 以及生产 H2 元数据一致性备份/回滚门禁维持未通过；本轮 P1 代码不会因此直接发布生产。

## P1-01 第一批：主题、组件和数据集结构化引用

- 新增 `analysis/AnalysisResourceGraph.java`，从主题 `content.widgets[]` 提取稳定组件 ID 和实际引用的多数据集 ID，支持兼容 V1 和 V2 组件配置，不以标题、备注或整个 JSON 字符串匹配判断数据集引用。
- 新增 `GET /api/dashboards/{id}/resources?published=false|true`：报告 `datasetIds`、`widgetIds`、`widgetCount`、`dashboardIds`、`dashboardCount` 以及 `missingDatasetIds`；读接口不会执行数据仓库 SQL。
- 分析主题保存时验证组件 ID 唯一、数据集确实存在，忽略客户端伪造的 `resourceIndex` 并重新生成；发布前复核引用，无效引用不发布。普通旧仪表板保持原 JSON 写入合同。
- 数据集删除改为结构化校验，覆盖 **草稿、已发布快照和历史版本**；标题误含数据集 ID 不再误阻止，无法读取的旧快照则安全拒绝，不新增表或修改已有历史快照。

## P1-01 第二批：同一主题下多个仪表板视图

- 采用可选、向后兼容的 `content.dashboards: [{id,name,widgetIds}]`；一个主题可配置 **1–30 个视图**，不同视图共享 `widgets[]`，不复制查询绑定或图表定义。
- 后端检查视图 ID 唯一、名称非空、`widgetIds` 全部是本主题现存组件且同视图不重复；禁止保存悬空引用。资源索引附带 dashboard view ID，版本发布保留视图配置。
- `AnalysisSubject.vue` 在预览区新增视图切换、新建、重命名、删除和已有组件多选；删除视图不会删除复用组件；新建组件自动纳入当前选中的视图；删除组件从所有视图剔除其 ID；保存和撤销/重做快照包含视图数据。
- 旧主题没有 `dashboards` 时，在编辑器内用单一默认视图呈现所有旧组件；首次保存才写入新的 `dashboards` 属性。旧普通仪表板编辑链路不强制升级。
- UI 遵循简洁中文企业产品风格，仅加紧凑选择器和操作控件，不加入介绍卡片或虚假数据。

## 本轮仅测试受影响模块

- 开发机隔离路径：`/opt/insight-bi-p0-preview`。
- `mvn -q -o -Dtest=AnalysisResourceGraphTest test`：**6/6 PASS**。覆盖多数据集、视图引用、保存/发布索引、历史数据集删除保护、旧仪表板合同、缺失和伪造引用。
- `mvn -q -o -Dtest=AnalysisResourceGraphTest,FineBiWorkspaceFolderTest test`：**成功**，附带只检查相关旧工作区合同。
- `npm run build`：Vue 3 / TypeScript / Vite **成功**。
- `node scripts/finebi_theme_views_ui_smoke.cjs`：隔离 Chromium 真实页面操作 + 模拟 REST，**成功**。验证两个视图共享组件、选择/重命名/保存/刷新恢复和删除视图不删组件。
- `bash -n scripts/p0_scoped_ci.sh` 和 `node --check scripts/finebi_theme_views_ui_smoke.cjs`：**成功**。CI 的文件变更测试映射包括 `AnalysisResourceGraphTest`。
- 未运行全量回归；没有进行 StarRocks 真数或生产发布。

## P1-01 未完成

- 更正式的主题资源元数据层（包括多主题复用组件与权限隔离）；
- 主题内仪表板视图的布局方案与独立发布/回滚策略（当前多个视图随主题整体一起保存和发布）；
- P1-02 数据编辑步骤引擎（行公式、过滤、聚合、同源 JOIN/UNION 的可组合步骤）；
- P1-03 现有可视化设计器集成（画布布局、联动、下钻、发布快照）。
- P0-05 DataForge StarRocks 实际 JDBC 对账、生产 H2 一致性备份和生产式回滚演练。

**下一步建议按计划进入 P1-01 的视图布局/版本依赖，再推进 P1-02；未完成项目不勾选通过。**
