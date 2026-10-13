# FineBI 6.X 官方文档多模块核对与 Insight BI 开发基线

- 核对日期：2026-10-13
- 官方 6.X 文档入口：https://help.fanruan.com/finebi6.X/
- 用户最初提供的链接（当前指向 FineBI 7.X）：https://help.fanruan.com/finebi/doc-view-263.html
- 自研代码：`/opt/insight-bi`（独立 Vue 3 / Spring Boot 工程）
- FineBI 参考安装：`/opt/FineBI6.0`（与自研程序物理隔离）
- 核对原则：每一项优先按官方 6.X 子页面描述落实真实业务流程。标记 **[详阅]** 的页面已检查内容，**[索引]** 表示已找到官方入口但没有声称完成逐条参数审阅。

## 0. 版本与范围

官方 `/finebi/doc-view-263.html` 现在默认显示 **7.X**。7.0 引入指标中心，并将“公共数据”调整为“数据目录”；不能按 7.X 的导航要求 6.0 的界面。
FineBI6.X 首页：https://help.fanruan.com/finebi6.X/ 。其首页标示近 1900 篇资料，本次主要核对与自助分析/平台管理直接有关的模块入口与多层子页面，**不是全部文章逐篇阅读完毕**。
官方 6.X 文档内部同时记录 6.0、6.0.2、6.0.10、6.0.13、6.0.16、6.0.17 与 6.1 的差异；某页展示的功能并不代表最初的 6.0 一定有。需要按本地实际版本分别验证。
本项目业务数据来源固定为 StarRocks 直连：保留与数据分析相关的计算、关联、透视等能力；不实现抽取型的数据复制、全局更新、定时抽取或 FineDataLink 作业；未来迁移工具是独立项目。

## 1. FineBI 的正确对象与操作主线

**目录（供查看者） → 我的分析（分析主题容器） → 主题中的数据 → 数据编辑/模型 → 组件分析 → 主题内仪表板编排 → 保存/发布/分享 → 查看与交互**。

分析主题不是一张仪表板。它可以容纳多张数据表、多个数据处理结果、多个组件和仪表板，另有预览、版本、协作、发布资源及数据血缘。必须在实体模型中区分：
- `AnalysisSubject` 分析主题容器，归属文件夹/所有者；主题版本。
- `SubjectDatasetRef` 主题对数据集的引用、源 SQL、主题内步骤、字段元数据和关联。
- `AnalysisStep` 可有序执行的数据变换步骤（SQL/AST 编译），每个步骤可启用/禁用及调整顺序。
- `Component` 组件图形配置、维度与指标、字段表达式、排序、前后过滤、格式和交互。
- `Dashboard` 主题内多个组件的布局/布局响应策略、筛选器、事件关系。
- `PublishedResource` 目录可见性、审批、发布快照及外部访问权限。

## 2. 我的分析与主题管理

**官方依据**

- [详阅] 分析主题概述：https://help.fanruan.com/finebi6.X/doc-view-1888.html
- [详阅] 管理我的分析：https://help.fanruan.com/finebi6.X/doc-view-1897.html
- [详阅] 管理分析主题：https://help.fanruan.com/finebi6.X/doc-view-1889.html
- [索引] 协作：https://help.fanruan.com/finebi6.X/doc-view-1895.html
- [索引] 分享协作概述：https://help.fanruan.com/finebi6.X/doc-view-1854.html

**二/三级功能**：文件夹与主题的树形结构；新建/编辑/复制/移动/重命名/删除；搜索、最近编辑、创建时间和名称排序；协作给我的；按已发布/已分享/开启公共链接筛选；批量选择、批量移动与删除。主题预览应能切换数据、组件、仪表板（部分版本含分析文档）。主题保存、版本管理、协作共享和发布不等同于单一“保存仪表板”按钮。

**自研对照**：已做目录嵌套/主题移动、基础主题存储与单管理员发布；缺搜索、批量管理、最近编辑、协作、细粒度发布/版本工作流、真实主题级数据管理及多仪表板实体。

## 3. 公共数据、SQL 数据集、模型

**官方依据**

- [详阅] 公共数据概述：https://help.fanruan.com/finebi6.X/doc-view-264.html
- [索引] 文件夹管理：https://help.fanruan.com/finebi6.X/doc-view-40.html
- [索引] 添加 SQL 数据集：https://help.fanruan.com/finebi6.X/doc-view-890.html
- [索引] 设置关联（关联视图）：https://help.fanruan.com/finebi6.X/doc-view-78.html
- [索引] 血缘分析：https://help.fanruan.com/finebi6.X/doc-view-77.html
- [索引] StarRocks 数据连接：https://help.fanruan.com/finebi6.X/doc-view-2038.html
- [索引] 新直连介绍：https://help.fanruan.com/finebi6.X/doc-view-1292.html
- [索引] 配置数据连接入门：https://help.fanruan.com/finebi6.X/doc-view-94.html

**二/三级功能**：数据连接、数据库表直接注册、SQL 数据集、表与字段元数据、文件夹、共享/发布自助数据集、关联视图、字段与表血缘、来源与影响分析；数据预览和模型视图；关联方向及基数应影响组件跨表联动。官方也介绍 Excel/抽取表，但本项目不做业务数据抽取和作业队列，Excel 如不需要可单列需求评估，不视为 StarRocks 直连版核心。

**自研对照**：已实现数据库连接、真实表发现、SELECT SQL 数据集、字段读取/预览、文件夹归档；缺直连可编辑表关联模型、JOIN 编译、多表语义层、血缘图、共享数据集生命周期与引用依赖展示。

## 4. 主题中的数据编辑和数据加工

**官方依据**

- [详阅] 添加数据概述：https://help.fanruan.com/finebi6.X/doc-view-1901.html
- [详阅] 编辑数据概述：https://help.fanruan.com/finebi6.X/doc-view-506.html
- [详阅] 上下合并（UNION ALL）：https://help.fanruan.com/finebi6.X/doc-view-513.html
- [详阅] 左右合并（JOIN）：https://help.fanruan.com/finebi6.X/doc-view-512.html
- [详阅] 新增公式列：https://help.fanruan.com/finebi6.X/doc-view-1526.html
- [索引] 新增列基础：https://help.fanruan.com/finebi6.X/doc-view-509.html
- [索引] 条件标签列：https://help.fanruan.com/finebi6.X/doc-view-2055.html
- [索引] 数据集过滤：https://help.fanruan.com/finebi6.X/doc-view-507.html
- [详阅] 分组汇总：https://help.fanruan.com/finebi6.X/doc-view-508.html
- [索引] 数据编辑使用指南：https://help.fanruan.com/finebi6.X/doc-view-2254.html
- [索引] 编辑数据学习路径：https://help.fanruan.com/finebi6.X/doc-view-825.html

**二/三级功能**：
1. 主题添加数据（公共数据、其他主题等）；多表选择、顺序管理、引用关系。
2. 数据预览、字段数值/文本/时间类型、字段重命名/隐藏/排序/类型转换、表结构和来源。
3. 分析步骤：过滤、排序、公式新增列、赋值列、条件标签、汇总列、日期/时间差、分组汇总、多表左右/上下合并。
4. 步骤历史：插入、编辑、复制/粘贴、禁用/恢复、分组、批量处理、备注、拖动重排；结果预览与校验。
5. JOIN 细节：合并类型（左/右/内/全）、多字段连接、重复键警告、空值语义；UNION 的字段对齐映射。
6. 直连与抽取限制不同：官方特别指出直连的 JOIN/UNION 不能跨两个不同物理数据源。本项目可用“同一个 StarRocks 连接内执行安全 SQL”体现。
7. 官方明确“直连数据集无需更新”；因此只需要预览/实时查询/显式刷新，不做抽取任务。

**自研对照**：现有系统提供保存 SQL 和图表实时查询，但**没有这套可编辑、可复现、可重排、可单步预览的数据加工流水线**。应先设计合法查询 AST/SQL 编译器和字段血缘，避免做虚假的“新增列”“JOIN”按钮。

## 5. 组件分析 / 表格 / 计算 / 图形属性（首要功能）

**官方依据**

- [详阅] 制作第一个组件：https://help.fanruan.com/finebi6.X/doc-view-1653.html
- [详阅] 制作表格组件：https://help.fanruan.com/finebi6.X/doc-view-1847.html
- [详阅] 制作图表组件：https://help.fanruan.com/finebi6.X/doc-view-1849.html
- [详阅] 图形属性：https://help.fanruan.com/finebi6.X/doc-view-219.html
- [索引] 过滤器入门：https://help.fanruan.com/finebi6.X/doc-view-2403.html
- [索引] 组件计算介绍：https://help.fanruan.com/finebi6.X/doc-view-1189.html
- [索引] 快速计算函数：https://help.fanruan.com/finebi6.X/doc-view-1499.html
- [索引] 分组表：https://help.fanruan.com/finebi6.X/doc-view-121.html

**真正的组件工作台**：待分析数据字段区（文本、数值、日期）、数据字段搜索、计算字段和明细过滤；图表类型/字段槽/图形属性；中间结果预览；组件操作；右侧或组件属性区；组件底部标签管理。拖入分析轴、维度、指标、颜色、大小、提示、标签/细粒度槽是不同的语义动作，不只是“选图+两个 Select”。

**字段和聚合层**：多维度、多指标；SUM/AVG/COUNT/MIN/MAX、字段别名/格式；同环比/占比/排名等快速计算、日期粒度、TopN/排序、聚合后再计算的比率（如 SUM(利润)/SUM(销售额)），不能简单地先逐行除法后 SUM。

**过滤引擎（关键）**：
- 计算前的明细过滤 = WHERE（过滤后参与聚合）。
- 聚合后结果过滤 = HAVING / 外层结果表达式（不改变被汇总的原始样本）。
- 全局看板筛选、组件局部筛选、下钻筛选之间应有明确优先级与生效范围。不能一律追加相同层级的 WHERE。

**图表与类型特性**：分组表/明细表/交叉表、指标卡、柱形/条形/折线/面积/饼/环/散点/组合，以及后续指标仪表盘、地图、漏斗、雷达、矩形树等；每类图表有专属配置。图形属性包含颜色、大小、提示、形状、连线、半径、角度、标签、细粒度。多指标图表应支持“全部指标属性”与“单独某一指标属性”两级配置，不能只用公共样式覆盖全部。

**自研对照**：新 `AnalysisSubject.vue` 已支持选数据集、单维度/指标、聚合、基础图表选择、真实后端查询、组件保存、预览、发布；原 `Designer.vue` 有一些多指标透视/排序等功能，但两个编辑器尚未统一。欠缺动态计算字段、分组表完整交互、前后过滤语义、多轴/多维字段槽、按指标设置图形属性、日期粒度和完整快速计算。

## 6. 仪表板设计、联动、钻取、过滤组件与参数

**官方依据**

- [详阅] 仪表板学习路径：https://help.fanruan.com/finebi6.X/doc-view-1514.html
- [详阅] 组件设置联动：https://help.fanruan.com/finebi6.X/doc-view-150.html
- [索引] 组件跳转简介：https://help.fanruan.com/finebi6.X/doc-view-1595.html
- [索引] 钻取简介：https://help.fanruan.com/finebi6.X/doc-view-1630.html
- [索引] 过滤组件概述：https://help.fanruan.com/finebi6.X/doc-view-382.html
- [详阅] 参数功能：https://help.fanruan.com/finebi6.X/doc-view-1905.html
- [详阅] 仪表板样式：https://help.fanruan.com/finebi6.X/doc-view-156.html
- [索引] 仪表板自适应：https://help.fanruan.com/finebi6.X/doc-view-2123.html
- [索引] 导出仪表板：https://help.fanruan.com/finebi6.X/doc-view-161.html
- [详阅] 组件交互专题：https://help.fanruan.com/finebi6.X/doc-view-1816.html

**二/三级功能**：
- 主题内添加/移除组件、拖动布局、复制组合、图层/主题/背景、样式、自适应、查看和发布。
- 组件联动、单向/双向依赖关系、关联字段映射、筛选条件传播、清除/取消联动。
- 组件跳转至其他仪表板/站点、带参数值传递；维度层级钻取和面包屑恢复。
- 时间/文本/数值等过滤组件与 SQL 参数绑定；局部/全局作用范围；默认值和重置。
- 文本、图片、Tab、Web 等附属组件；表格和看板 Excel/PDF 导出。
- 交互细节：默认联动可基于相同数据集或主题模型关联；自定义联动需要依赖字段对应；不同来源组件的条件不能不加校验地共享；源组件一次点击替换自身旧条件，目标组件接收多个源条件时可做 AND 合并。

**自研对照**：现有旧 `Designer.vue` 有布局、部分筛选和下钻；新分析主题仅组件顺序预览，暂时**不是可拖拽编排的主题仪表板**。应合并两者，保留分析主题 → 组件 → 仪表板 → 发布的一个流程。

## 7. 分享、发布、目录与权限

**官方依据**

- [详阅] 管理系统简介：https://help.fanruan.com/finebi6.X/doc-view-168.html
- [详阅] 用户管理：https://help.fanruan.com/finebi6.X/doc-view-170.html
- [详阅] 权限：https://help.fanruan.com/finebi6.X/doc-view-726.html
- [详阅] 发布管理：https://help.fanruan.com/finebi6.X/doc-view-171.html
- [索引] 目录管理：https://help.fanruan.com/finebi6.X/doc-view-169.html
- [索引] 资源迁移：https://help.fanruan.com/finebi6.X/doc-view-441.html

**二/三级功能**：
- 用户、部门/职务、角色、平台设计用户/查看用户；用户导入、同步和禁用。
- 权限载体为部门/职务/角色/个人。资源权限至少区分访问、编辑、管理、授权；数据连接、数据集、目录、仪表板等需独立控制，必要时还包括数据行/列规则。
- 设计者申请发布，管理员审批，目录可见；数据集可发布到公共数据；申请取消发布、撤回、取消挂载；可过滤发布申请与历史。
- 目录导航与“我的分析”文件夹不是同一个对象。查看者不应该自动看到所有草稿/私有主题。
- 资源交接、回收站、审计/监控、备份迁移属于治理运维。

**自研对照**：当前单管理员 Session + CSRF、直接发布快照、无组织权限模型；与 FineBI 发布审批和用户可见性相距较大。正式企业使用应优先建立身份/权限和只读查看侧规则。

## 8. 管理、运行与选择性实施

**官方管理系统子模块（按管理系统简介索引）**：
- 系统管理（登录、通用参数、缓存、邮件）；
- 用户与组织、角色、平台使用用户；
- 数据连接管理、数据/目录/报表权限、发布审批；
- 平台外观、移动平台；
- 定时调度、负载管理、Web 集群、备份还原、资源迁移、回收站、插件与注册；
- 故障排查、运行监控等。

以上为成熟商业产品的覆盖面，不是新 StarRocks BI 核心分析流程的第一优先级。非必要商业插件、注册、抽取/更新功能不应为了“1:1”盲目加入。适用于企业的必须项包括用户组织、权限、发布、审计、限流与运行监控。

## 9. 直连 StarRocks：明确的设计约束

- 业务数据只留在 StarRocks；MySQL 只保存元数据/组件定义/权限/版本，不做复制数据。
- 所有可视化数据变换都需编译成经白名单和参数绑定校验的 SQL，由服务器执行；必要时使用子查询、CTE、分组聚合和窗口函数。
- 不应任意拼接自由输入到 SQL 标识符、ORDER BY、JOIN、计算字段。
- 需要元数据类型推导、SQL 查询超时、并发限制、取消执行、LIMIT/采样预览、慢查询分析、日志脱敏。
- JOIN/UNION 仅处理同一受控 StarRocks 实例/连接中的源表，避免暗中引入跨源 ETL。
- 计算结果可能依赖“先过滤再聚合”“先聚合再计算”和窗口上下文，必须先定义计算语义再做 UI。

## 10. 开发次序与验收标准

| 阶段 | 重点 | 可验证完成条件 |
|---|---|---|
| P0-1 | 统一分析主题的数据、组件、仪表板模型 | 在一个主题内添加两张已有数据集、多组件、多个仪表板，保存重进后完全恢复 |
| P0-2 | 完整组件分析槽位/多维多指标 | 维度、指标、颜色、提示、大小、标签、细粒度绑定均改变真实查询/图表结果 |
| P0-3 | 前后过滤、动态计算和 SQL 编译 | 用真实 StarRocks 表验证 WHERE 与 HAVING 结果不同且正确，拒绝非法表达式 |
| P0-4 | 表格分析 | 分组表/明细表/交叉表的排序、汇总、展开、格式、合计正确 |
| P1-1 | 数据编辑步骤、JOIN、UNION、公式列 | 步骤可以编辑、排序、取消应用及预览，每个结果由后端执行且正确 |
| P1-2 | 仪表板完整编排 | 图表组件可拖拽、调整布局、组合、筛选器联动、查看发布版本 |
| P1-3 | 参数、联动与钻取 | 自定义依赖字段映射和多来源筛选正确传播，支持清除和恢复 |
| P1-4 | 权限/发布生命周期 | 不同用户只能看到被授权数据和目录，发布/撤回受控 |
| P2 | 导出/高级图表/条件格式/平台治理 | 针对每个可用入口运行真实数据/浏览器定向测试 |
| P3 | 独立迁移程序 | 不侵入当前 BI 工程，按照新产品数据结构导入历史资产 |

## 11. 质量与版权边界

- 逐个官方子页面提炼为验收行为，不盲目复制页面。每一项 UI 都要有对应 API、SQL/业务逻辑、持久化、错误处理和定向测试。
- 严禁使用 FineBI 专有源码、图片、图标、字体和品牌标识；借鉴可观察到的分析交互和工作逻辑，自主实现。
- 保持原 FineBI `37799`、Datart `8087`、DataForge `8080`、独立 BI `18110` 隔离。
- 该报告是官方核心链路和多级页面的研究索引，**并不表示官方全部约 1898 篇文档已经逐篇审阅**。
