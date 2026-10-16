# 统一分析工作台第二批：键盘布局控制（2026-10-16）

## 本批范围
在现有 `AnalysisSubject.vue` 的一个分析主题、多视图共享组件结构上，增加键盘可达布局编辑。复用 `dashboardLayout.ts`、原数据源/Widget、修订号保存、撤销/重做和发布快照，不新增设计器、数据库表或 HTTP API，也不修改旧 V1 Designer。

- 画布的拖拽手柄原已支持 Tab 聚焦，但不能使用键盘执行操作。本批允许 **方向键** 移动当前视图组件前后、**Home / End** 移至首尾；**Shift + 左右方向键** 设置 6/12 栏宽；**Shift + 上下方向键** 在 320/480/640px 内调整高度。
- 操作仅修改当前视图的 `widgetIds` 和 `layout`；同主题另一视图和全局共享 `widgets[]` 不受影响。无效 ID、抵达边界和不支持的按键不触发 `mark()`；键盘操作走原有撤销/重做和持久化路径。
- 历史缺省 380px 高度继续按旧值显示；如用户主动通过键盘调整宽度并保存，则会取当前支持的最接近规范高度 480px，不写入非法高度。
- 增加紧凑操作提示、aria-keyshortcuts 和屏幕阅读器 aria-live 通告。事件只在拖动手柄聚焦且匹配快捷键时拦截，不抢占 Tab、Ctrl/Alt/Meta 或表单键盘行为。
- 原鼠标拖动、前后移、宽高按钮继续保留，已发布视图不显示编辑用的拖动手柄。

## 定向验收
`node --experimental-strip-types --test scripts/analysis_workspace_layout.test.mjs` 负责跨视图隔离、键盘顺序与宽高范围的纯逻辑测试；`scripts/analysis_workspace_browser_smoke.cjs` 负责真实 Chromium 的拖动、键盘重排/缩放、复制、共享编辑、保存/重开和发布只读渲染。`.github/workflows/analysis-workspace-scoped.yml` 为变更路径触发的专项 CI；TypeScript/Vite 构建由现有 `P0 Changed-Area Checks` 执行。

## 环境限制与后续
本批请求时远程开发机显示离线，当前工作只能通过授权 GitHub 接口修改和提交代码，由 Actions 执行隔离测试。未在服务器上运行 npm / Java / MySQL / StarRocks，**不能把 CI 验证称为真实开发机或生产验证**。未部署 `18110` 服务，未覆盖真实业务数据库。

下一批优先完善仪表板内的可用筛选组件与现有 V2 查询的连接，并约束跨数据集过滤到受服务器校验的稳定字段 ID；完成前不挂占位按钮。
