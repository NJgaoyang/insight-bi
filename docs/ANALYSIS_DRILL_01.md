# ANALYSIS-DRILL-01：视图内受控点选钻取

继承已有 jumps 目标视图跳转。新增视图的 drills[sourceWidgetId] 显式开关，仅当来源组件与目标筛选字段具有相同数据集和稳定 fieldId 时，点击维度值进入目标视图并应用过滤。值仅驻留浏览器，不持久化。未开启时保持原无过滤跳转。

读者调用已存在的 /reader/dashboards/{id}/widgets/{widgetId}/query，使用发布配置批准的 viewId/filterValue，后端再次校验发布快照、数据集字段及行列权限。删除/变更组件、视图、跳转目标后清理无效钻取配置。跨数据集隐式映射、任意钻取路径不支持。

定向：Node 脚本 scripts/analysis_drill_path.test.mjs；前端 npm run build；Chromium scripts/analysis_drill_browser_smoke.cjs；专项 CI .github/workflows/analysis-drill-scoped.yml。开发机离线，未部署线上 18110，未运行真实 MySQL/StarRocks。
