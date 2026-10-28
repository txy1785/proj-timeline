# 项目记忆

本文件记录当前已确认的产品决定、代码现状和开发上下文。每次需求改变后更新对应条目；实施过程和旧版本验证记录保留在 [PLAN.md](PLAN.md)，不要把历史记录当成当前契约。

## 项目定位

- 名称：项目时间线。面向约 10 人以内团队的自托管进度管理系统。
- 业务主线：项目 → 功能模块 → 工作项；工作项是最小功能交付单位，包含负责人、日期、功能说明、交付资料与关联 Bug。项目级工作项和项目阶段目标继续保留。现有 tasks 非 BUG 行作为历史任务保留，tasks.kind=BUG 继续承载独立问题记录，记录与附件的 id/归属不批量改写。
- `docs/需求文档与产品设计方案.md` 说明产品需求；`docs/接口设计.md` 说明 HTTP 路径、权限和统计口径；`docs/database.sql` 是新库 DDL 参考；实际应用升级由 `backend/src/main/resources/db/migration/` 中的 Flyway 脚本完成。
- `prototype/` 是早期视觉参考，界面实现以 `frontend/src/` 为准。
- 2026-10-27 已先交付改善文档与原型，随后用户明确授权按方案改动。已实施 [体验改善设计方案-v1](docs/体验改善设计方案-v1.md)：我的工作、项目概览、统一工作项与任务详情、独立日期行时间线、设置/周报流程；验收见 [记录](docs/体验改善验收/README.md)。静态图册仍是视觉参考，正式契约见需求和接口设计。

## 用户已经确认的关键决定

1. 项目开始日期必填，项目完成日期可以不填。项目完成日期未知时仍可创建和维护项目。
2. 阶段性目标直接属于**项目**，包含名称、可选说明和一个目标日期（可选的量化指标见第 9 条）；一个项目可以有多个目标。目标日期不得早于项目开始日期；项目完成日期存在时也不得晚于该日期。
3. 模块是**功能划分**，包含名称、可选描述和一位负责人。模块没有阶段开始/完成日期，也不承载项目阶段性目标。Flyway V2 曾临时加入模块日期，V3 已移除；不得依据 V2 恢复这一设计。
4. 模块负责人进入自己的模块，应能查看工作项并填写新工作项的名称、说明、计划起止日期和负责人。模块卡片与时间线模块名称均通向模块工作页。新建工作项默认继承模块负责人，可改派给项目内有效且非 VIEWER 的成员。
5. “本周实际人天”按每日工时明细的 `work_date` 在自然周内汇总。更正未发布周的工时会改变实时周报；已发布周报保留发布时的 JSON 快照。
6. **2026-10-27 用户批准功能交付模型，覆盖旧任务完成率规则**：工作项负责人开始并确认交付，不要求创建子任务，不由旧任务/交付资料数量阻断交付；状态 DONE=已交付，兼容 progress=100，其他状态 progress=0，进行中不再估算50%。模块/项目按未取消工作项的已交付数 ÷ 总数计算完成率，界面优先显示已交付x/y。阶段目标量化进度仍独立。
7. **产品定位不做 OKR**（2026-10 明确）。团队按项目交付为主、项目结束后进入维护期，管理方式是排期抓进度，目前没有季度量化目标、评分与对齐流程，负责人也不熟悉 OKR。因此不引入 Objective/KeyResult、周期、评分、对齐等概念；`阶段性目标` 仍是“带日期的项目里程碑”，不升级为 O。重新评估的触发条件：真正按季度定 3–5 条量化目标且有人维护、目标达成度要跨部门比较或与绩效挂钩、超过 10 人需要逐层对齐——满足两条以上再讨论。
8. **交付资料**：功能页统一展示交付要求及实际文件，复用原 node_deliverables 和 NODE 附件。清单项可选关联同一工作项的未删除文件，不新建文件体系，不作为自动完成门槛。负责人按约定检查资料后确认交付；已交付/取消工作项仍沿用原清单“只改状态”限制，文件按原权限保留上传、预览和版本记录。
9. **阶段性目标的量化指标是“可选附件式”扩展，不是 OKR**：`metricName / metricUnit / metricTarget / metricCurrent` 四列全空就是原来的日期型里程碑；填了才由 `ProgressRules.metricProgress` 派生 `progress`（当前值 ÷ 目标值，四舍五入，夹 0–100，超额按 100）。指标不参与项目/模块/工作项进度聚合，不进周报，没有周期、评分、对齐、信心指数。这样回答“季度目标达成多少”但不需要引入 OKR 的方法论。
10. **模块变更申请**（成员改别人模块的唯一通道）：只覆盖模块的**名称 / 描述 / 负责人**，没有模块编辑权限的项目成员（非 VIEWER）提交并可填申请说明，PM/ADMIN 审批。同一模块同时只允许一个待审批申请（`pending_module_id` 生成列 + 唯一键兜底）；通过时在**同一事务**内改模块并记录审批人/时间，驳回必须填原因；申请人可撤回自己待审批的申请。已有直接编辑权限的人（PM、该模块负责人）调用提交接口返回 409。审批结果与待审批各发一条站内通知（通知枚举扩到 7 类）。
11. **独立 Bug**：成员报告必须关联同项目工作项，未开始/进行中/已交付均可，已取消不可新报；默认执行人为功能负责人。不再自动寻找进行中的项目级 BUGFIX。已交付功能的 Bug 可继续修复、改派、记录和附件，保留功能交付状态与日期。Bug 复用 TODO/DOING/DONE（待修复/修复中/已解决），接口/storage 保留 tasks.kind=BUG；旧分流 Bug 的有效同项目 related_node_id 作为业务来源，否则回退 node_id。来源职务及执行人决定能力，不得跨项目追溯；归档与 VIEWER 只读。

12. **体验主线**：USER 登录默认“我的工作”，展示本人负责的功能工作项和本人待处理 Bug；ADMIN/VIEWER 默认项目总览。工作项详情默认功能说明、交付资料、Bug、活动记录，有旧任务时才显示历史任务入口。旧任务/看板/通知深链仍可读，默认不再新建通用子任务。阶段目标在项目设置、模块只作功能分组；新建空模块默认可见。2026-10-28 恢复独立“模块”导航和列表页，项目概览直接显示模块卡片，明确项目 → 模块 → 工作项。
13. **当前风险与负载**：当前延期/受阻仅计未关闭功能；人员负载主要展示有效项目的未交付/进行中/延期/本周到期工作项和未解决/逾期 Bug，数字与展开同范围。旧任务人天字段/API和已发布周报快照保留历史兼容，不用 Bug 或资料数换算功能进度。

14. **进展记录是命名的 Markdown 文件**（2026-10-28 用户指定）：Bug/历史任务修复区的记录编辑区支持 Markdown，保存时命名并生成一份 `.md` 记录（缺后缀自动补），一个任务可保存多份，按时间倒序展示。粘贴/拖入的图片与文件立即上传为**本任务附件**（分类 `TASK_RECORD`「进展记录附件」），正文只保存 `attachment:{id}`：图片用 Blob 内联预览，其他文件显示为可点击超链接（预览或下载），并同步出现在「附件」页签。编辑区列出本次上传并可移除（同时软删除任务附件、去掉正文占位）；焦点在编辑区内时预览态粘贴会切回编辑；非文件拖放不拦截。记录只追加不覆盖，可下载 `.md`；删除限作者本人或管理者。Markdown 渲染固定 `html:false`，不允许原始 HTML；正文上限 20000 字，记录名 ≤120 字且禁止路径字符。附件仍按任务权限校验，不改四层文件体系。

## 当前实现定位

| 内容 | 当前位置 |
|---|---|
| 工作台读取与业务上下文 | `backend/src/main/java/com/projtimeline/workspace/`、`frontend/src/views/MyWorkView.vue` |
| 项目概览与共享上下文 | `frontend/src/views/ProjectOverviewView.vue`、`stores/project.ts`、`stores/app.ts`、`composables/useProjectContext.ts` |
| 当前功能交付契约与实施状态 | `docs/功能交付模型实施计划.md`（本轮用户授权覆盖旧默认任务流程） |
| 项目、成员、模块、阶段目标与时间线业务 | `backend/src/main/java/com/projtimeline/project/` |
| 工作项、任务、逐日工时、工作项交付物 | `backend/src/main/java/com/projtimeline/work/` |
| 进度派生与聚合口径 | `backend/src/main/java/com/projtimeline/common/ProgressRules.java`（工作项、模块/项目聚合、阶段性目标量化进度）、`project/ProjectService.java`（模块/项目聚合） |
| 项目权限 | `backend/src/main/java/com/projtimeline/security/PermissionService.java` |
| 项目设置与阶段目标编辑 | `frontend/src/views/ProjectSettingsView.vue` |
| 工作项/模块工作页 | `frontend/src/views/WorkItemsView.vue`、`ModulesView.vue`（独立模块列表）、`ModuleDetailView.vue` |
| 项目时间线、工作项详情 | `frontend/src/views/TimelineView.vue`、`NodeDetailView.vue` |
| 路由、会话、HTTP 客户端 | `frontend/src/router.ts`、`stores/auth.ts`、`api/client.ts` |
| 项目、模块、工作项与任务的文档附件 | `backend/src/main/java/com/projtimeline/file/`、`frontend/src/components/AttachmentPanel.vue`、`AttachmentPreview.vue`、`ProjectDocsView.vue`、`utils/attachment.ts` |
| 进展记录（命名 Markdown 文件） | 后端 `work/WorkService.java`（`taskNotes/addTaskNote`、`noteTitle`）、Flyway V13；前端 `components/MarkdownEditor.vue`、`MarkdownView.vue`、`utils/markdown.ts`、`TaskDrawer.vue`、全局 `.markdown-body` 样式 |
| 模块变更申请 | 后端 `project/ChangeRequestService.java`（提交/列表/审批/撤回）与 `project/ChangeRequestController.java`；前端 `components/ChangeRequestDialog.vue`（模块页提交）、`views/ProjectSettingsView.vue`（审批列表） |
| 站内通知写入口 | `backend/src/main/java/com/projtimeline/notification/NotificationService.java`（读取与已读仍在 `report/ReportingService.java`） |
| 工作项交付物清单 | 口径 `backend/src/main/java/com/projtimeline/common/DeliverableRules.java`，读写 `work/WorkService.java`；前端 `frontend/src/components/DeliverablePanel.vue`（接入 `NodeDetailView.vue`，时间线计数在 `TimelineView.vue`） |
| 工作项类型、项目级工作项与缺陷提交 | 后端 `work/WorkService.java`（`createNode` 的 `moduleId` 可空、`reportBug` 路由）；前端 `components/WorkItemFormDialog.vue`、`BugReportDialog.vue`、`utils/workItem.ts`；时间线项目级泳道在 `TimelineView.vue`，模块视图项目级区块在 `ModulesView.vue` |

- 后端：Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios；使用 npm 和 `package-lock.json`。文件预览额外依赖 `docx-preview` 与 `read-excel-file`（按需动态加载，不进入主包）；Markdown 渲染依赖 `markdown-it`（`html:false`，随工作项详情抽屉加载）。
- 当前 Flyway 版本为 V13。V1 建表，V2 将项目开始日期设为必填并过渡性增加模块日期，V3 建立 `project_stage_targets` 并移除模块日期，V4 建立 `attachments` 文档附件表，V5 只修正 `owner_type` 注释（V4 已应用到环境，不得再改写），V6 建立 `task_notes` 并让 `attachments.owner_type` 支持 TASK，V7 只把 `nodes.progress` 列注释改为「已弃用」（进度改为任务完成率派生，保留列与历史值，不删列），V8 给 `tasks` 增加 `situation`/`handling` 并为 `node_logs.action` 增加 `TASK_UPDATE`/`TASK_DELETE`，V9 建立 `node_deliverables` 并为 `node_logs.action` 增加 `DELIVERABLE_ADD`/`DELIVERABLE_UPDATE`/`DELIVERABLE_DELETE`，V10 给 `project_stage_targets` 增加四个可空指标列（`metric_name/metric_unit/metric_target/metric_current`），V11 建立 `module_change_requests` 并把 `notifications.type` 扩到 7 类，V12 让 `nodes.module_id` 可空、增加 `nodes.type`，给 `tasks` 增加 `kind` 和 `related_node_id`，V13 给 `task_notes` 增加 `title`（默认「进展记录.md」）并把 `content` 扩为 `MEDIUMTEXT` 存 Markdown。已有迁移文件不能改写；新变更追加 V14 及以后版本。
- 当前 OpenAPI 有 75 个 `/api` 操作：本轮新增工作项交付/独立Bug两个工作台读取接口，并保留此前上下文、历史任务、管理事项等读取。前端 `frontend/src/api/schema.d.ts` 已由运行中的新版后端生成；默认脚本端口 8080，本机服务其他端口需显式指定。
- 认证为 8 小时 JWT，仅保存用户 ID；每次请求重新检查账号启用状态与角色。浏览器会话存于 `sessionStorage`。
- 日期判定使用注入的 `Clock`，默认业务时区 `Asia/Shanghai`。延期和临近到期由服务端实时计算。
- 演示数据只在显式执行 `docs/demo_seed.sql` 时导入；不属于 Flyway 正式迁移。根目录 `.env` 和本机数据库凭据不得写入文档或提交。

## 权限和数据边界

- 全局角色：`ADMIN`、`USER`、`VIEWER`。ADMIN 可管理全部项目；VIEWER 可全局读取业务数据，但不能写入业务数据；USER 只读取其加入的项目。
- 每个项目恰有一个有效 PM。PM 管项目、成员、模块、阶段目标和周报。模块负责人管理自己模块下的工作项和任务；工作项负责人管理自己的工作项和任务；任务执行人管理自己的任务状态和工时。项目级工作项由 PM/ADMIN 创建，负责人可为项目内任意有效成员，PM 兜底。实际授权必须由后端校验，前端按钮可见性只是提示。
- 归档项目保留可读历史；除恢复项目外，项目业务写操作被拒绝。改派目标必须是本项目有效且非 VIEWER 的成员。
- 文档与附件分四层：项目级由 PM 上传，模块级由模块负责人上传，工作项级由工作项负责人/模块负责人/PM 上传，任务级由执行人/工作项负责人/模块负责人/PM 上传；删除限上传者本人或对应层级负责人。下载统一按所属项目校验，VIEWER 可下载不可上传，归档项目只读。删除为软删除，磁盘文件保留以维持痕迹；删除模块时其模块文档一并软删除。项目文档页「全部文件」汇总四层文件并标明归属。
- 默认流程不再新建通用任务；现有非 BUG 任务、工时、说明、记录、附件保留历史入口。历史任务关闭父项后仍沿原限制不能改内容/状态。Bug 在来源功能已交付后仍可独立修改内容/状态；来源取消后内容/状态禁止修改，记录/附件沿原权限。
- 工作项与 Bug 快捷状态带 expectedStatus；服务端行锁校验，冲突返回409并刷新，文字草稿保留。Bug内容编辑不可悄悄改变关联功能或脱离Bug类型。
- 文档内容类型由服务端按扩展名推导，不采用客户端声明；仅 PDF 与图片内联展示，其余强制下载，并带 `X-Content-Type-Options: nosniff`。
- 在线预览在浏览器内完成，不新增服务端接口、不引入转换服务：PDF/图片用浏览器原生能力，txt/md/csv 用只读文本，docx 用 docx-preview，xlsx 用 read-excel-file；`doc/xls/ppt` 等旧格式与压缩包只提供下载。
- 进展记录（`task_notes`）的 Markdown 渲染固定在浏览器完成，`markdown-it` 以 `html:false` 配置转义用户 HTML；正文中的 `attachment:{id}` 只由前端解析成本任务附件的 Blob 或下载链接，服务端不解析、不执行 Markdown，也不把 token 放进 URL。
- 预览的安全边界：SVG 不提供预览（blob: 文档继承应用源，脚本可在新标签页执行）；docx 渲染必须保持 `renderAltChunks/renderChanges/renderComments: false`（altChunk 会把内嵌 HTML 渲染成同源 iframe），渲染后还要清洗超链接（只留 http(s)/mailto/锚点）。PDF 走浏览器内置阅读器（独立扩展源），未加 iframe `sandbox`，原因与待验证项见 PLAN。改动这两个库的选项前先看 PLAN 里的 PoC 记录；Markdown 渲染器同理不得开启原始 HTML。
- 模块变更申请是成员改别人模块的**唯一通道**：只覆盖名称/描述/负责人，PM/ADMIN 审批，通过后同一事务内生效；同一模块同时只有一个待审批；撤回仅申请人。审批时会重新校验负责人是项目内有效且非 VIEWER 的成员。
- 工作项交付物清单的写入权限与工作项级附件一致（工作项负责人/模块负责人/PM，VIEWER 与归档项目只读）；关联文件必须是同一工作项未删除的附件，但**只在真的改关联时才校验**——文件被软删除后，清单仍可只改状态（否则关闭工作项上文件下拉禁用会让该项永远标不成“已提供”）。已完成/已取消工作项只能改交付物状态，新增、改名、改派、换文件、改备注、改排序、删除一律 409。完全无变化的更新直接返回、不写日志；清单是物理删除，不删除关联文件。
- 项目/模块交付完成率只计未取消工作项；DONE已交付计1，进行中不估算完成量。兼容百分比由ProgressRules派生，界面显示交付计数；工作项的资料/Bug/历史任务均不改变功能进度。周报按周一至周日归周，已发布快照不改。

## 验证与已知边界

- 2026-10-26 的体验走查问题现已按批准方案实施：修正工作项/模块的项目导航、增加我的工作与独立项目概览、按日期独立分行的时间线、按项目职务显示操作。历史问题证据保留在 [走查记录](docs/用户体验走查与简化建议.md)，不代表当前仍存在。

- 功能交付轮次验证：Maven 71 项测试通过；前端 lint/typecheck/build 通过；Playwright 当时 29 项通过；另在明确命名的本机隔离 MySQL 库执行真实 HTTP 与浏览器流程。详细命令、截图、边界见 [功能交付验收](docs/功能交付验收/README.md)。2026-10-28 模块入口恢复后前端 lint/typecheck/build、完整 Playwright 31 项通过（含 320px 窄屏）；用户原库只读确认仍有 12 条模块记录，5173 页面与 58080 健康接口均返回 200。
- 2026-10-28 Markdown 进展记录轮次验证：Maven 72 项测试通过；前端 lint/typecheck/build 通过；Playwright 34 项通过（含命名 .md + 粘贴截图进正文和附件、正文图片真实内联、粘贴附件可移除、预览态粘贴切回编辑）；在本机隔离 MySQL 库 `proj_timeline_mdnote_verify` 执行 V1–V13 迁移并跑真实 HTTP（登录、上传 `TASK_RECORD` 附件、保存/读取 Markdown 记录、文件名与长度校验），验证后删除测试库并停止临时服务。随后重启 58080 后端（原 IntelliJ 进程已停止，改由 `mvn spring-boot:run` 后台运行），V13 已应用到本机 `proj_timeline`（迁移前该表 0 条记录），健康接口 UP、OpenAPI 仍 75 个操作；5173 页面 HTTP 200、`/api` 代理返回 401（未登录，符合预期）。独立审核两轮均为“可合并”、无 P1/P2；功能提交 `12b127c` 以合并提交 `d02d578` 集成 develop 并推送。
- `PLAN.md` 中早期接口/页面/表数量是历史快照。当前接口 75 个（本轮未新增路径）、表 16 张（含 flyway_schema_history）；V13 只扩列不建表。
- 当前工作站此前未运行 Docker Compose 的真实构建；部署仍需在具备 Docker 的目标环境验证。
- 文件存储当前用本地磁盘（`app.storage.root` 默认 `./data/files`，容器内 `/app/data/files` 挂 `file-data` 卷）。未接入 MinIO/对象存储：存储层已抽成 `FileStorage` 接口，切换时只加实现并改配置。单文件上限 50MB，Nginx 侧 `client_max_body_size 60m`，后端 `spring.servlet.multipart` 与 `app.storage.max-file-bytes` 同步。
- Git 远程：`https://github.com/txy1785/proj-timeline.git`。分支模型：`main` 版本上线、`develop` 开发主线、`feature/<描述>` 功能分支从 `develop` 拉出；功能代码必须经独立审核（干净会话/子代理）后合并 `develop`，发布从 `develop` 合并 `main` 并打 tag。GitHub 默认分支为 `main`，远程仅保留 `main` 与 `develop`（旧 `master` 已删除）。提交前先 `git status` 核对未被 .gitignore 遗漏的文件。
- 曾在本机启动 Vite 5173 与后端供用户试用（后端默认端口 8080，用户本机 `.env` 设 `SERVER_PORT=58080`，当前实际监听 58080）；进程是否仍运行应实时检查，不能依据本文假定。2026-10-28 后端已改为 `mvn spring-boot:run` 后台进程，`UPLOAD_ROOT` 显式指向仓库根 `data/files` 以与历史文件一致；本轮重启后浏览器会话（sessionStorage）仍在，但如遇登录页属会话过期正常现象。
- 2026-10-27 起本地试用服务需支持局域网访问：`frontend/vite.config.ts` 固定 `server.host: true`（监听 0.0.0.0、`strictPort`），局域网客户端访问 `http://<本机IP>:5173` 即可，`/api`、`/actuator` 仍由 Vite 服务端代理到后端 58080，客户端无需直连后端端口；不要再以 `--host 127.0.0.1` 启动开发服务。后端 58080 已监听全网卡（Java 双栈），Windows 防火墙对 node.exe（Private 网络）有入站放行；换机器或换网段时若仍拒绝访问，先查网络配置文件与防火墙入站规则，而不是改应用代码。
