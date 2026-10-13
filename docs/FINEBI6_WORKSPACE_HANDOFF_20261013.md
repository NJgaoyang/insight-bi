# FineBI 6.0 参考与 Insight BI 自研改造交接（2026-10-13）

## 用户目标与约束

以开发机上已安装的 FineBI 6.0 真实操作体验为参考，改进独立 BI 系统。重点是 **FineBI 自助分析组件编辑器**（不是 FineReport 单元格/打印报表）。直接从 StarRocks 查询业务数据，不做 ETL、抽取、数据同步，也不复制 FineBI 商业源码、图标、图片、字库或商标。迁移程序未来另做。

## 参考 FineBI 实例的已验证事实

- 安装目录：`/opt/FineBI6.0`；启动脚本：`/opt/FineBI6.0/bin/finebi start`；配置端口 `37799`。
- 通过本机 `http://127.0.0.1:37799/webroot/decision` HTTP 302 验证，真实页面显示 `FineBI商业智能` 登录页面。
- 开发机保存了之前用授权浏览器访问时捕获的实际界面：`/tmp/finebi-after-login.png`、`/tmp/finebi-audit-analysis.png`、`/tmp/finebi-audit-publicdata.png`、`/tmp/finebi-audit-system.png` 和 `/tmp/finebi-subject-edit-stage5.png`。
- 主工作区结构：深蓝窄功能导航栏；我的分析（树形文件夹、新建分析主题）；公共数据（目录、数据集）；系统管理；分析主题内包含组件编辑、数据字段、分析结果及看板。这些截图没有提供完整的所有图表参数及真实业务数据操作证明，不能据此宣称 1:1 还原。

## 本轮已实现的代码（独立自研）

分支：`feat/finebi6-workspace-20261013`；工程 `/opt/insight-bi`；线上部署端口 `18110`。

1. `backend/.../DatabaseStore.java`：无损增加 `insight_folder`、`insight_data_folder`，为 `insight_dashboard` 添加 `folder_id`、`kind`，为 `insight_dataset` 添加 `folder_id`。既有看板默认 `kind=dashboard`，不覆盖旧数据。
2. `backend/.../BiApi.java`：创建/列出/重命名/删除带依赖保护的分析文件夹及公共数据文件夹；移动分析主题与数据集；新建分析主题 `kind=analysis`，兼容原有看板 API；仍使用原 CSRF、登录鉴权。
3. `frontend/src/App.vue`：FineBI 风格深蓝窄导航栏、我的分析文件夹树、目录及主题网格、公共数据文件夹树和管理、基础系统管理入口，全部通过真实 REST API。
4. `frontend/src/AnalysisSubject.vue`：数据集选择、真实字段（维度/指标）、点击及拖拽绑定、图表类型、指标聚合、右侧属性面板、组件标签页、多个组件的看板预览、保存与发布。真正的图表数据调用已有 `ChartWidget` 和 Spring Boot `/api/query`，再由后端运行已保存的 StarRocks SELECT。
5. `frontend/src/style.css`：独立实现的紧凑企业分析界面，不采用 FineBI 版权素材。

## 仅测试修改区域

- `cd /opt/insight-bi/backend && mvn -q -Dtest=FineBiWorkspaceFolderTest,BiQueryInTest test`
- `cd /opt/insight-bi/frontend && npm run build`
- `node /opt/insight-bi/scripts/finebi_workspace_ui_smoke.cjs`
- `node /opt/insight-bi/scripts/finebi_public_data_ui_smoke.cjs`
- `node /opt/insight-bi/scripts/finebi_analysis_ui_smoke.cjs`
- 前端浏览器测试用模拟授权 API 验证真实事件与请求参数，不等于使用生产 StarRocks 凭据的数据分析端到端测试。
- 正式服务 `insight-bi.service`、`http://106.54.222.247:18110`，原 FineBI `37799`，其他项目未修改。
- 部署前 MySQL 表备份在 `/opt/insight-bi/runtime/finebi6-workspace-predeploy-20261013-121228.sql`，权限 `600`，不要提交 Git。

## 仍缺失（严格不宣称完成 1:1）

- 细粒度用户、组织、行列/菜单资源权限；当前独立 BI 只有单管理员。
- 完整 FineBI 组件参数体系、筛选器联动、复杂计算字段、公共数据关联和多表模型、预计算/多数据集组合分析。
- 完整分析主题内仪表板自由编排（本轮为组件顺序预览）；当前独立 BI 的常规/自由画布设计器独立保留。
- 完整系统管理、发布资源权限、分享流程、更多可视化类型、手机布局、多人协作。
- StarRocks 实际生产数据和不同数据权限用户的端到端验收与性能测试。
- 正式 HTTPS、访问网关、并发与性能优化。公网 HTTP 登录不应使用重要密码。

## 下一阶段建议

优先将当前自助分析组件与栅格仪表板编排统一成分析主题工作流程（组件分析→仪表板设计→发布）；数据集字段计算与 StarRocks SQL 编译；真实用户/角色/组织行列权限；全量图表属性与更多图表；移动端适配；最后独立迁移程序。

此文档为本轮可复核的开发交接，不是“FineBI 已实现 1:1”的声明。
