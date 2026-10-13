# Insight BI · 双模式企业可视化系统

这是独立研发的 Vue 3 / TypeScript + Spring Boot 3 / Java 21 BI 系统，不是 FineBI 或腾讯云 BI 商业产品源码。


## 下一阶段可直接执行的研发计划

- **总实施计划**：[\`FINEBI_DEVELOPMENT_EXECUTION_PLAN.md\`](FINEBI_DEVELOPMENT_EXECUTION_PLAN.md) — 架构、P0～P3、质量门禁、部署与回滚约束。
- **P0 开发任务书**：[\`docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md\`](docs/FINEBI_ANALYSIS_ENGINE_P0_TASKS.md) — 字段模型、SQL Planner、公式、前后过滤、多维多指标组件分析和真实 StarRocks 验收。
- **官方 6.X 子页面核对**：[\`docs/FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md\`](docs/FINEBI6X_OFFICIAL_MODULE_MAP_20261013.md)。

第一步为 **P0-00 基线冻结与环境检查**，不要直接从 UI 模仿或 FineBI JAR 复用开始。当前两份计划文件只包含待执行任务，并不表示计算引擎已实现。

## 核心功能（增量 v0.3）
- 单管理员 Session 登录、CSRF 校验；无任何演示报表或业务数据。
- MySQL / StarRocks 数据连接的创建、测试、编辑、删除及依赖保护，连接密码 AES-256-GCM 加密储存。
- 数据库真实表发现、只读 SELECT 数据集、动态读取字段、预览 SQL 数据（带行数限制）。
- 常规仪表板（GridStack）与自由画布（Moveable）、模式转换、拖拽、缩放。
- ECharts 柱状、条形、折线、面积、饼/环、散点、双轴组合图、指标卡；明细表、交叉表、文本、筛选器。
- 维度、指标、聚合、基本样式和字段级表格属性配置；筛选器同字段联动。
- 保存、乐观版本号、防覆盖、发布快照、取消发布、只读查看、删除。
- 默认空工作区，严格不预装内置数据。当前查询上限 500 行、预览上限 100 行。

## 部署位置与端口
- 代码：/opt/insight-bi/
- 服务：insight-bi.service
- 监听：0.0.0.0:18110（已开放公网；必须做好账号和网络安全）
- 本机查看： http://127.0.0.1:18110/
- 电脑访问： http://106.54.222.247:18110/ （无需 SSH 隧道）
- 初始用户名 admin；随机生成的密码保存在服务器 /opt/insight-bi/runtime/local.env ，权限 600，不提交 Git。
- 已切换到 MySQL 172.17.0.12:3306 的 datasphere 数据库（仅 insight_ 前缀元数据表）。原 H2 数据库已备份，不再承载在线数据。

## MySQL 元数据库配置（当前已启用）
目标：172.17.0.12:3306/datasphere；所有元数据表名统一以 insight_ 开头。
1. 将 /opt/insight-bi/runtime/mysql.env.example 复制到 /opt/insight-bi/runtime/mysql.env
2. 仅在服务器上填写 SPRING_DATASOURCE_USERNAME / SPRING_DATASOURCE_PASSWORD；文件权限 600。
3. systemctl restart insight-bi ; systemctl status insight-bi --no-pager。
4. 首次启动将自动创建 insight_connection、insight_dataset、insight_dashboard、insight_dashboard_version 四张表。
如需从 H2 转移已有报表，请先导出迁移，不要直接切换元数据源丢失当前内容。

## 配置真实数据连接
管理页面 > 数据连接 > 新建连接：
- StarRocks 类型，172.17.0.12，9030，目标业务库 ods
- MySQL 类型，172.17.0.12，3306，目标数据库 datasphere
使用数据库专用最小权限账号。支持连接测试及基于实际表创建数据集。
服务端查询仅允许单条 SELECT，绑定筛选参数，设定查询超时和最大返回行数。建议为业务数据源使用只读账号，避免在 BI 内保存数据库 root 凭据。

## 更新
bash /opt/insight-bi/scripts/deploy.sh
# 安全部署脚本先构建前端、备份旧 JAR、停止服务，再构建后端并验证新服务
每次只运行改动模块对应的定向测试，如 scripts/api_smoke.py、scripts/ui_smoke.cjs。

## 尚未完成
更高级的多层级透视表、公式编辑器、多租户细粒度权限、SeaTunnel 作业编排、Excel 导入、图表下钻、版本差异、报表分享及权限配置、SQL 解析 AST、导出任务管理、完整条件格式等还在待开发清单。不要把当前增量 v0.3 当作全量完成的企业 BI。


## 2026-10-12 开发增量与公网交付
- 支持从工作台导出、导入报表 JSON 配置（不包含数据库密码），用于备份与迁移。
- Insight BI：公网入口 http://106.54.222.247:18110/，服务仍为 systemd insight-bi。
- 原 Enterprise BI：保留在 18120 端口，没有删除或迁移其业务数据库。
- 数据库迁移：独立 MySQL datasphere.insight_connection / insight_dataset / insight_dashboard / insight_dashboard_version，不会改动 ods。
- 历史版本：每次发布生成快照，可查看历史并恢复指定快照为草稿，当前已发布快照不随恢复而改变。
- 设计器：图表点击联动筛选；一键清除筛选；复制/粘贴组件样式；自由画布图层置顶/置底；明细表按列配置多规则条件格式。
- 验证：指定增量 API、浏览器设计器/表格操作、前端 TypeScript 和 Maven 编译，不做无关模块全量回归。
- 安全提醒：当前直连 HTTP 无 TLS，不适合在公网输入高价值密码；需配置域名+HTTPS 或限制 18110 来源 IP。生产数据库连接建议改为专用最小权限用户，避免长期使用 root。

## 2026-10-12 · v0.3 增量
- 图表支持逐级下钻维度、面包屑返回；下钻条件仅作用于当前图表，末级仍可启用跨组件联动。
- 图表支持维度或指标排序（升序/降序），Top N 通过后端真实 LIMIT 和 ORDER BY 实现。
- 交叉表支持同一行列分组下多个数值指标，以及多指标的行合计、列合计与总计。
- 筛选器支持单选或多选，多选通过后端参数化 IN (?,...) 实现，最多 50 项。
- JSON 报表配置导入导出完成浏览器定向测试；报告仍由真实元数据库持久化。
- 相关自动检查：scripts/drill_ui_smoke.cjs、scripts/pivot_ui_smoke.cjs、scripts/multiselect_ui_smoke.cjs、scripts/backup_ui_smoke.cjs 和后端 BiQueryInTest。
- 尚未完成：Excel 上传、通用公式引擎、SeaTunnel 编排、完整用户/数据权限、多报表联动与高级透视表。
