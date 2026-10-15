# 分析工作台功能完善 · 第一批：统一仪表板组件编辑（2026-10-15）

## 背景及本批交付

功能清单评估后，先补强已存在的 `AnalysisSubject.vue`（分析主题 → 共享组件 → 多仪表板），不新建第三套设计器，不强制迁移旧的 `Designer.vue` 报表。旧报表继续沿用旧模式，避免破坏生产历史快照。

本次交付的是**真正在前端可操作并保存的分析仪表板画布**：

1. **组件拖放排序**：仪表板视图内每张组件卡片新增显式“⋮⋮”拖放手柄。拖到另一组件前方即可改变当前视图的 `widgetIds` 顺序；无效 ID、跨视图拖入、拖到自身不会更新状态，也不会改 SQL、图表查询配置或其他视图。
2. **同画布选择与组件分析编辑**：每张卡片可“选中”并联动右侧当前组件属性，可点击“编辑”进入原组件分析工作台，在已有维度、指标、公式、样式配置中继续编辑同一个共享 `widgets[]` 对象，避免出现画布编辑和组件编辑两份不一致的配置。
3. **复制仪表板视图**：保留当前视图已选择组件、顺序、宽高布局，生成全新视图 ID；复制的是 `dashboardViews[]` 的布局元数据，而不是一整套独立 `widgets[]`。两个视图可各自修改布局和顺序，共享组件的数据和样式设置；过滤掉无效布局引用和旧视图自跳转。
4. **保存和发布兼容**：沿用现有 `PUT /api/dashboards/{id}` 的修订号校验和发布快照机制，`DashboardView.widgetIds/layout` 继续保持已有数据格式，不新建数据库表、不修改既有 API；撤销/重做仍使用分析主题原有快照栈。已发布页面只渲染画布布局，不显示编辑拖放手柄。
5. **简约布局**：新增低干扰拖动手柄、当前选中状态描边和拖动半透明提示；原有“组件前移/后移”和“切换宽高”按钮不删除，保留键鼠操作兜底。

## 变动文件

- `frontend/src/analysis/dashboardLayout.ts`：新增纯函数 `reorderViewWidget`、`duplicateDashboardView`，仅在视图内改变引用排序并复制独立布局元数据。
- `frontend/src/AnalysisSubject.vue`：拖放事件、视图复制、画布中直接选中和跳转编辑共享组件。
- `frontend/src/style.css`：紧凑拖动手柄、选中态、拖动态。
- `scripts/analysis_workspace_layout.test.mjs`：使用 Node 22 TS 类型擦除测试视图独立、无效拖动拒绝、引用不复制、布局/跳转安全。
- `scripts/analysis_workspace_browser_smoke.cjs`：模拟 REST 环境 Chromium 测试拖拽 → 复制 → 编辑 → 保存/重开 → 已发布视图顺序与只读性。
- `.github/workflows/analysis-workspace-scoped.yml`：只在该工作台相关文件变化时运行上述定向验证。

## 验收约束

- 前端 TypeScript / Vite 构建由现有 `P0 Changed-Area Checks` 负责。
- 纯逻辑测试 `node --experimental-strip-types --test scripts/analysis_workspace_layout.test.mjs`；浏览器测试通过独立 CI 中的隔离 Vite 服务和 mocked API 完成，无需连接真实 StarRocks/MySQL。
- 如果浏览器专项失败，则保留失败结果并修复本批涉及的代码，不把静态构建成功说成端到端通过。当前没有做全量回归或生产部署。

## 后续仍未完成（不夸大）

**这只是统一工作台的第一批。** 旧 `Designer.vue` 的传统仪表板与 `AnalysisSubject.vue` 的 V2 组件目前仍是两个历史模型。尚未实现旧报表字段引用到 V2 稳定 fieldId 的安全转换、跨数据集语义关联可视化、多层级分组表、跨数据集下钻筛选、完整图表专属属性、多人协作编辑及批量模型迁移。没有进行旧报表的强制转换或自动覆盖。
