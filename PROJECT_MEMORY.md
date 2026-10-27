# 项目记忆

本文件记录当前已确认的产品决定、代码现状和开发上下文。每次需求改变后更新对应条目；实施过程和旧版本验证记录保留在 [PLAN.md](PLAN.md)，不要把历史记录当成当前契约。

## 项目定位

- 名称：项目时间线。面向约 10 人以内团队的自托管进度管理系统。
- 业务主线：项目 → 功能模块 → 工作项 → 任务；项目下另有**项目级工作项**（不属于功能模块），承接跨模块的测试、维护、缺陷修复和上线保障。项目还有独立的阶段性目标、成员、周报。逐日工时挂在任务下。工作项另有**交付物清单**，回答“这个工作项要交什么、还缺什么、文件在哪”。
- `docs/需求文档与产品设计方案.md` 说明产品需求；`docs/接口设计.md` 说明 HTTP 路径、权限和统计口径；`docs/database.sql` 是新库 DDL 参考；实际应用升级由 `backend/src/main/resources/db/migration/` 中的 Flyway 脚本完成。
- `prototype/` 是早期视觉参考，界面实现以 `frontend/src/` 为准。
- 2026-10-27 已先交付改善文档与原型，随后用户明确授权按方案改动。已实施 [体验改善设计方案-v1](docs/体验改善设计方案-v1.md)：我的工作、项目概览、统一工作项与任务详情、独立日期行时间线、设置/周报流程；验收见 [记录](docs/体验改善验收/README.md)。静态图册仍是视觉参考，正式契约见需求和接口设计。

## 用户已经确认的关键决定

1. 项目开始日期必填，项目完成日期可以不填。项目完成日期未知时仍可创建和维护项目。
2. 阶段性目标直接属于**项目**，包含名称、可选说明和一个目标日期（可选的量化指标见第 9 条）；一个项目可以有多个目标。目标日期不得早于项目开始日期；项目完成日期存在时也不得晚于该日期。
3. 模块是**功能划分**，包含名称、可选描述和一位负责人。模块没有阶段开始/完成日期，也不承载项目阶段性目标。Flyway V2 曾临时加入模块日期，V3 已移除；不得依据 V2 恢复这一设计。
4. 模块负责人进入自己的模块，应能查看工作项并填写新工作项的名称、说明、计划起止日期和负责人。模块卡片与时间线模块名称均通向模块工作页。新建工作项默认继承模块负责人，可改派给项目内有效且非 VIEWER 的成员。
5. “本周实际人天”按每日工时明细的 `work_date` 在自然周内汇总。更正未发布周的工时会改变实时周报；已发布周报保留发布时的 JSON 快照。
6. 工作项进度不手填：工作项进度 = 已完成任务数 ÷ 任务总数；没有任务的工作项按状态估算（未开始 0、进行中 50、已完成 100），DONE 固定 100，未全部完成时最高显示 99。完成工作项前必须先把该工作项任务全部完成；已完成/已取消工作项不能新增任务，任务状态也不能再变更；取消工作项下未完成的任务不计入人员负载与周报计划人天。原手动进度接口已移除；`nodes.progress` 列只保留历史值，不再读写。
7. **产品定位不做 OKR**（2026-10 明确）。团队按项目交付为主、项目结束后进入维护期，管理方式是排期抓进度，目前没有季度量化目标、评分与对齐流程，负责人也不熟悉 OKR。因此不引入 Objective/KeyResult、周期、评分、对齐等概念；`阶段性目标` 仍是“带日期的项目里程碑”，不升级为 O。重新评估的触发条件：真正按季度定 3–5 条量化目标且有人维护、目标达成度要跨部门比较或与绩效挂钩、超过 10 人需要逐层对齐——满足两条以上再讨论。
8. **工作项交付物清单**（替代“把目标改成 OKR”的方案）：一个工作项一份**平铺清单**，字段为名称、状态（待提供/已提供）、负责人、备注，可选关联**同一工作项**的一个未删除附件。不区分“需要/产出”方向（归属由工作项表达，也没有跨工作项依赖），不新建文件体系（文件仍走 `attachments` 的工作项级文件），不参与工作项进度，**不作为完成工作项的前置条件**，只在工作项详情与时间线显示“已提供 x/y”。已完成/已取消工作项只能改状态，其余变更返回 409。
9. **阶段性目标的量化指标是“可选附件式”扩展，不是 OKR**：`metricName / metricUnit / metricTarget / metricCurrent` 四列全空就是原来的日期型里程碑；填了才由 `ProgressRules.metricProgress` 派生 `progress`（当前值 ÷ 目标值，四舍五入，夹 0–100，超额按 100）。指标不参与项目/模块/工作项进度聚合，不进周报，没有周期、评分、对齐、信心指数。这样回答“季度目标达成多少”但不需要引入 OKR 的方法论。
10. **模块变更申请**（成员改别人模块的唯一通道）：只覆盖模块的**名称 / 描述 / 负责人**，没有模块编辑权限的项目成员（非 VIEWER）提交并可填申请说明，PM/ADMIN 审批。同一模块同时只允许一个待审批申请（`pending_module_id` 生成列 + 唯一键兜底）；通过时在**同一事务**内改模块并记录审批人/时间，驳回必须填原因；申请人可撤回自己待审批的申请。已有直接编辑权限的人（PM、该模块负责人）调用提交接口返回 409。审批结果与待审批各发一条站内通知（通知枚举扩到 7 类）。
11. **工作项模型（原“节点”升级）**：模块只做功能划分；模块下是工作项，项目下允许**项目级工作项**（`nodes.module_id` 为空），承接跨模块的测试、维护、缺陷修复和上线保障。工作项类型为 `FEATURE/TEST/MAINTENANCE/BUGFIX/OTHER`；任务类型为 `FEATURE/FIX/BUG/TEST/OTHER`，并可用 `related_node_id` 关联原工作项。成员提缺陷走 `POST /projects/{id}/bugs`：关联工作项进行中则落到该工作项，否则落到当前进行中的项目级 BUGFIX 工作项（没有则 409）；VIEWER 与归档项目拒绝。项目级工作项由 PM/ADMIN 创建，负责人可为任意有效成员；模块工作项仍由模块负责人/PM 管理。模块进度只算本模块工作项，项目进度算全部工作项（含项目级）。

12. **体验主线**：USER 登录默认进入“我的工作”；ADMIN/VIEWER 默认进入项目总览。项目内固定概览、工作项、时间线、文档、周报、设置导航。模块负责人直接编辑本模块名称/描述/负责人，创建/删除/排序仍仅 PM/ADMIN。任务状态提交 expectedStatus，记录独立保存；权限变化保留可复制草稿。
13. **当前风险与负载展开**：当前受阻风险只计未关闭工作项；关闭项保留历史字段。人员负载的未完成/本周到期数字展开分别与原数字同范围；本周计划人天包含已完成任务，使用 WORKLOAD_PLAN 按周展开。统计数不改变进度算法。

## 当前实现定位

| 内容 | 当前位置 |
|---|---|
| 工作台读取与业务上下文 | `backend/src/main/java/com/projtimeline/workspace/`、`frontend/src/views/MyWorkView.vue` |
| 项目概览与共享上下文 | `frontend/src/views/ProjectOverviewView.vue`、`stores/project.ts`、`stores/app.ts`、`composables/useProjectContext.ts` |
| 项目、成员、模块、阶段目标与时间线业务 | `backend/src/main/java/com/projtimeline/project/` |
| 工作项、任务、逐日工时、工作项交付物 | `backend/src/main/java/com/projtimeline/work/` |
| 进度派生与聚合口径 | `backend/src/main/java/com/projtimeline/common/ProgressRules.java`（工作项、模块/项目聚合、阶段性目标量化进度）、`project/ProjectService.java`（模块/项目聚合） |
| 项目权限 | `backend/src/main/java/com/projtimeline/security/PermissionService.java` |
| 项目设置与阶段目标编辑 | `frontend/src/views/ProjectSettingsView.vue` |
| 工作项/模块工作页 | `frontend/src/views/WorkItemsView.vue`、`ModuleDetailView.vue`；`ModulesView.vue` 为旧路由兼容 |
| 项目时间线、工作项详情 | `frontend/src/views/TimelineView.vue`、`NodeDetailView.vue` |
| 路由、会话、HTTP 客户端 | `frontend/src/router.ts`、`stores/auth.ts`、`api/client.ts` |
| 项目、模块、工作项与任务的文档附件 | `backend/src/main/java/com/projtimeline/file/`、`frontend/src/components/AttachmentPanel.vue`、`AttachmentPreview.vue`、`ProjectDocsView.vue`、`utils/attachment.ts` |
| 模块变更申请 | 后端 `project/ChangeRequestService.java`（提交/列表/审批/撤回）与 `project/ChangeRequestController.java`；前端 `components/ChangeRequestDialog.vue`（模块页提交）、`views/ProjectSettingsView.vue`（审批列表） |
| 站内通知写入口 | `backend/src/main/java/com/projtimeline/notification/NotificationService.java`（读取与已读仍在 `report/ReportingService.java`） |
| 工作项交付物清单 | 口径 `backend/src/main/java/com/projtimeline/common/DeliverableRules.java`，读写 `work/WorkService.java`；前端 `frontend/src/components/DeliverablePanel.vue`（接入 `NodeDetailView.vue`，时间线计数在 `TimelineView.vue`） |
| 工作项类型、项目级工作项与缺陷提交 | 后端 `work/WorkService.java`（`createNode` 的 `moduleId` 可空、`reportBug` 路由）；前端 `components/WorkItemFormDialog.vue`、`BugReportDialog.vue`、`utils/workItem.ts`；时间线项目级泳道在 `TimelineView.vue`，模块视图项目级区块在 `ModulesView.vue` |

- 后端：Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios；使用 npm 和 `package-lock.json`。文件预览额外依赖 `docx-preview` 与 `read-excel-file`（按需动态加载，不进入主包）。
- 当前 Flyway 版本为 V12。V1 建表，V2 将项目开始日期设为必填并过渡性增加模块日期，V3 建立 `project_stage_targets` 并移除模块日期，V4 建立 `attachments` 文档附件表，V5 只修正 `owner_type` 注释（V4 已应用到环境，不得再改写），V6 建立 `task_notes` 并让 `attachments.owner_type` 支持 TASK，V7 只把 `nodes.progress` 列注释改为「已弃用」（进度改为任务完成率派生，保留列与历史值，不删列），V8 给 `tasks` 增加 `situation`/`handling` 并为 `node_logs.action` 增加 `TASK_UPDATE`/`TASK_DELETE`，V9 建立 `node_deliverables` 并为 `node_logs.action` 增加 `DELIVERABLE_ADD`/`DELIVERABLE_UPDATE`/`DELIVERABLE_DELETE`，V10 给 `project_stage_targets` 增加四个可空指标列（`metric_name/metric_unit/metric_target/metric_current`），V11 建立 `module_change_requests` 并把 `notifications.type` 扩到 7 类，V12 让 `nodes.module_id` 可空、增加 `nodes.type`，给 `tasks` 增加 `kind` 和 `related_node_id`。已有迁移文件不能改写；新变更追加 V13 及以后版本。
- 当前 OpenAPI 有 73 个 `/api` 操作：增加业务上下文、工作台任务/管理事项/负载工作项与缺陷接收预判 5 个读取接口。前端 `frontend/src/api/schema.d.ts` 已由运行中的新版后端生成；默认脚本端口 8080，本机服务其他端口需显式指定。
- 认证为 8 小时 JWT，仅保存用户 ID；每次请求重新检查账号启用状态与角色。浏览器会话存于 `sessionStorage`。
- 日期判定使用注入的 `Clock`，默认业务时区 `Asia/Shanghai`。延期和临近到期由服务端实时计算。
- 演示数据只在显式执行 `docs/demo_seed.sql` 时导入；不属于 Flyway 正式迁移。根目录 `.env` 和本机数据库凭据不得写入文档或提交。

## 权限和数据边界

- 全局角色：`ADMIN`、`USER`、`VIEWER`。ADMIN 可管理全部项目；VIEWER 可全局读取业务数据，但不能写入业务数据；USER 只读取其加入的项目。
- 每个项目恰有一个有效 PM。PM 管项目、成员、模块、阶段目标和周报。模块负责人管理自己模块下的工作项和任务；工作项负责人管理自己的工作项和任务；任务执行人管理自己的任务状态和工时。项目级工作项由 PM/ADMIN 创建，负责人可为项目内任意有效成员，PM 兜底。实际授权必须由后端校验，前端按钮可见性只是提示。
- 归档项目保留可读历史；除恢复项目外，项目业务写操作被拒绝。改派目标必须是本项目有效且非 VIEWER 的成员。
- 文档与附件分四层：项目级由 PM 上传，模块级由模块负责人上传，工作项级由工作项负责人/模块负责人/PM 上传，任务级由执行人/工作项负责人/模块负责人/PM 上传；删除限上传者本人或对应层级负责人。下载统一按所属项目校验，VIEWER 可下载不可上传，归档项目只读。删除为软删除，磁盘文件保留以维持痕迹；删除模块时其模块文档一并软删除。项目文档页「全部文件」汇总四层文件并标明归属。
- 任务不要求录工时：用状态（待完成/进行中/已完成）标记进度。逐日工时的表与接口保留、历史数据不动，但界面不再提供录入口。任务另有「任务情况」「处理方式」两个说明字段、追加式进展记录（`task_notes`，带作者与时间，可当备注）和附件；任务可编辑（名称/描述/情况/处理方式/改派/截止日/计划人天）和删除（同时删除记录与工时、附件软删除）。
- 任务列表提供快捷状态按钮（待完成→开始、进行中→完成、已完成→重新开始），提交时带 `expectedStatus`，状态已被他人改变会返回 409 并刷新列表。
- 文档内容类型由服务端按扩展名推导，不采用客户端声明；仅 PDF 与图片内联展示，其余强制下载，并带 `X-Content-Type-Options: nosniff`。
- 在线预览在浏览器内完成，不新增服务端接口、不引入转换服务：PDF/图片用浏览器原生能力，txt/md/csv 用只读文本，docx 用 docx-preview，xlsx 用 read-excel-file；`doc/xls/ppt` 等旧格式与压缩包只提供下载。
- 预览的安全边界：SVG 不提供预览（blob: 文档继承应用源，脚本可在新标签页执行）；docx 渲染必须保持 `renderAltChunks/renderChanges/renderComments: false`（altChunk 会把内嵌 HTML 渲染成同源 iframe），渲染后还要清洗超链接（只留 http(s)/mailto/锚点）。PDF 走浏览器内置阅读器（独立扩展源），未加 iframe `sandbox`，原因与待验证项见 PLAN。改动这两个库的选项前先看 PLAN 里的 PoC 记录。
- 模块变更申请是成员改别人模块的**唯一通道**：只覆盖名称/描述/负责人，PM/ADMIN 审批，通过后同一事务内生效；同一模块同时只有一个待审批；撤回仅申请人。审批时会重新校验负责人是项目内有效且非 VIEWER 的成员。
- 工作项交付物清单的写入权限与工作项级附件一致（工作项负责人/模块负责人/PM，VIEWER 与归档项目只读）；关联文件必须是同一工作项未删除的附件，但**只在真的改关联时才校验**——文件被软删除后，清单仍可只改状态（否则关闭工作项上文件下拉禁用会让该项永远标不成“已提供”）。已完成/已取消工作项只能改交付物状态，新增、改名、改派、换文件、改备注、改排序、删除一律 409。完全无变化的更新直接返回、不写日志；清单是物理删除，不删除关联文件。
- 项目进度按未取消工作项的进度直接平均；模块进度只对本模块未取消工作项直接平均；项目级工作项计入项目进度、不计入任何模块进度。工作项进度由 `ProgressRules` 按任务完成率派生（无任务时按状态估算，DONE 固定 100，未全部完成最高 99），不提供手动填写。延期、临近、受阻分别保留标记。周报按周一至周日归周。

## 验证与已知边界

- 2026-10-26 的体验走查问题现已按批准方案实施：修正工作项/模块的项目导航、增加我的工作与独立项目概览、按日期独立分行的时间线、按项目职务显示操作。历史问题证据保留在 [走查记录](docs/用户体验走查与简化建议.md)，不代表当前仍存在。

- 本轮验证：Maven 55 项测试通过；前端 lint/typecheck/build 通过；Playwright 15 项角色、任务与体验回归通过；另在明确命名的本机隔离 MySQL 库执行真实 HTTP 与浏览器流程。详细命令、截图、边界见 [验收记录](docs/体验改善验收/README.md)。
- `PLAN.md` 中早期接口/页面/表数量是历史快照。当前接口 73 个、表 16 张（含 flyway_schema_history），本轮无数据库结构或迁移变更；不可把早期数字当现状。
- 当前工作站此前未运行 Docker Compose 的真实构建；部署仍需在具备 Docker 的目标环境验证。
- 文件存储当前用本地磁盘（`app.storage.root` 默认 `./data/files`，容器内 `/app/data/files` 挂 `file-data` 卷）。未接入 MinIO/对象存储：存储层已抽成 `FileStorage` 接口，切换时只加实现并改配置。单文件上限 50MB，Nginx 侧 `client_max_body_size 60m`，后端 `spring.servlet.multipart` 与 `app.storage.max-file-bytes` 同步。
- Git 远程：`https://github.com/txy1785/proj-timeline.git`。分支模型：`main` 版本上线、`develop` 开发主线、`feature/<描述>` 功能分支从 `develop` 拉出；功能代码必须经独立审核（干净会话/子代理）后合并 `develop`，发布从 `develop` 合并 `main` 并打 tag。GitHub 默认分支为 `main`，远程仅保留 `main` 与 `develop`（旧 `master` 已删除）。提交前先 `git status` 核对未被 .gitignore 遗漏的文件。
- 曾在本机启动 Vite 5173 与后端 8080 供用户试用；进程是否仍运行应实时检查，不能依据本文假定。最近浏览器停在登录页，可能只是会话过期。
