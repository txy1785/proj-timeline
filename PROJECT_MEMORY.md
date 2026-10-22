# 项目记忆

本文件记录当前已确认的产品决定、代码现状和开发上下文。每次需求改变后更新对应条目；实施过程和旧版本验证记录保留在 [PLAN.md](PLAN.md)，不要把历史记录当成当前契约。

## 项目定位

- 名称：项目时间线。面向约 10 人以内团队的自托管进度管理系统。
- 业务主线：项目 → 功能模块 → 节点 → 任务；项目还有独立的阶段性目标、成员、周报。逐日工时挂在任务下。节点另有**交付物清单**，回答“这个节点要交什么、还缺什么、文件在哪”。
- `docs/需求文档与产品设计方案.md` 说明产品需求；`docs/接口设计.md` 说明 HTTP 路径、权限和统计口径；`docs/database.sql` 是新库 DDL 参考；实际应用升级由 `backend/src/main/resources/db/migration/` 中的 Flyway 脚本完成。
- `prototype/` 是早期视觉参考，界面实现以 `frontend/src/` 为准。

## 用户已经确认的关键决定

1. 项目开始日期必填，项目完成日期可以不填。项目完成日期未知时仍可创建和维护项目。
2. 阶段性目标直接属于**项目**，包含名称、可选说明和一个目标日期；一个项目可以有多个目标。目标日期不得早于项目开始日期；项目完成日期存在时也不得晚于该日期。
3. 模块是**功能划分**，包含名称、可选描述和一位负责人。模块没有阶段开始/完成日期，也不承载项目阶段性目标。Flyway V2 曾临时加入模块日期，V3 已移除；不得依据 V2 恢复这一设计。
4. 模块负责人进入自己的模块，应能查看节点并填写新节点的名称、说明、计划起止日期和负责人。模块卡片与时间线模块名称均通向模块工作页。新建节点默认继承模块负责人，可改派给项目内有效且非 VIEWER 的成员。
5. “本周实际人天”按每日工时明细的 `work_date` 在自然周内汇总。更正未发布周的工时会改变实时周报；已发布周报保留发布时的 JSON 快照。
6. 节点进度不手填：节点进度 = 已完成任务数 ÷ 任务总数；没有任务的节点按状态估算（未开始 0、进行中 50、已完成 100），DONE 固定 100，未全部完成时最高显示 99。完成节点前必须先把该节点任务全部完成；已完成/已取消节点不能新增任务，任务状态也不能再变更；取消节点下未完成的任务不计入人员负载与周报计划人天。原手动进度接口已移除；`nodes.progress` 列只保留历史值，不再读写。
7. **产品定位不做 OKR**（2026-10 明确）。团队按项目交付为主、项目结束后进入维护期，管理方式是排期抓进度，目前没有季度量化目标、评分与对齐流程，负责人也不熟悉 OKR。因此不引入 Objective/KeyResult、周期、评分、对齐等概念；`阶段性目标` 仍是“带日期的项目里程碑”，不升级为 O。重新评估的触发条件：真正按季度定 3–5 条量化目标且有人维护、目标达成度要跨部门比较或与绩效挂钩、超过 10 人需要逐层对齐——满足两条以上再讨论。
8. **节点交付物清单**（替代“把目标改成 OKR”的方案）：一个节点一份**平铺清单**，字段为名称、状态（待提供/已提供）、负责人、备注，可选关联**同一节点**的一个未删除附件。不区分“需要/产出”方向（归属由节点表达，也没有跨节点依赖），不新建文件体系（文件仍走 `attachments` 的节点级文件），不参与节点进度，**不作为完成节点的前置条件**，只在节点详情与时间线显示“已提供 x/y”。已完成/已取消节点只能改状态，其余变更返回 409。

## 当前实现定位

| 内容 | 当前位置 |
|---|---|
| 项目、成员、模块、阶段目标与时间线业务 | `backend/src/main/java/com/projtimeline/project/` |
| 节点、任务、逐日工时、节点交付物 | `backend/src/main/java/com/projtimeline/work/` |
| 进度派生与聚合口径 | `backend/src/main/java/com/projtimeline/common/ProgressRules.java`（节点）、`project/ProjectService.java`（模块/项目聚合） |
| 项目权限 | `backend/src/main/java/com/projtimeline/security/PermissionService.java` |
| 项目设置与阶段目标编辑 | `frontend/src/views/ProjectSettingsView.vue` |
| 模块卡片与模块工作页 | `frontend/src/views/ModulesView.vue`、`ModuleDetailView.vue` |
| 项目时间线、节点详情 | `frontend/src/views/TimelineView.vue`、`NodeDetailView.vue` |
| 路由、会话、HTTP 客户端 | `frontend/src/router.ts`、`stores/auth.ts`、`api/client.ts` |
| 项目、模块、节点与任务的文档附件 | `backend/src/main/java/com/projtimeline/file/`、`frontend/src/components/AttachmentPanel.vue`、`AttachmentPreview.vue`、`ProjectDocsView.vue`、`utils/attachment.ts` |
| 节点交付物清单 | 口径 `backend/src/main/java/com/projtimeline/common/DeliverableRules.java`，读写 `work/WorkService.java`；前端 `frontend/src/components/DeliverablePanel.vue`（接入 `NodeDetailView.vue`，时间线计数在 `TimelineView.vue`） |

- 后端：Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios；使用 npm 和 `package-lock.json`。文件预览额外依赖 `docx-preview` 与 `read-excel-file`（按需动态加载，不进入主包）。
- 当前 Flyway 版本为 V9。V1 建表，V2 将项目开始日期设为必填并过渡性增加模块日期，V3 建立 `project_stage_targets` 并移除模块日期，V4 建立 `attachments` 文档附件表，V5 只修正 `owner_type` 注释（V4 已应用到环境，不得再改写），V6 建立 `task_notes` 并让 `attachments.owner_type` 支持 TASK，V7 只把 `nodes.progress` 列注释改为「已弃用」（进度改为任务完成率派生，保留列与历史值，不删列），V8 给 `tasks` 增加 `situation`/`handling` 并为 `node_logs.action` 增加 `TASK_UPDATE`/`TASK_DELETE`，V9 建立 `node_deliverables` 并为 `node_logs.action` 增加 `DELIVERABLE_ADD`/`DELIVERABLE_UPDATE`/`DELIVERABLE_DELETE`。已有迁移文件不能改写；新变更追加 V10 及以后版本。
- 当前 OpenAPI 有 63 个 `/api` 操作（V9 增加 4 个交付物接口）。前端生成类型在 `frontend/src/api/schema.d.ts`，后端运行时用 `npm --prefix frontend run api:types` 更新（脚本默认 8080，本机后端在 58080 时需手动指定 URL）。`schema.d.ts` 目前不被应用代码引用，未重新生成不影响类型检查，但接口变更后应补跑。
- 认证为 8 小时 JWT，仅保存用户 ID；每次请求重新检查账号启用状态与角色。浏览器会话存于 `sessionStorage`。
- 日期判定使用注入的 `Clock`，默认业务时区 `Asia/Shanghai`。延期和临近到期由服务端实时计算。
- 演示数据只在显式执行 `docs/demo_seed.sql` 时导入；不属于 Flyway 正式迁移。根目录 `.env` 和本机数据库凭据不得写入文档或提交。

## 权限和数据边界

- 全局角色：`ADMIN`、`USER`、`VIEWER`。ADMIN 可管理全部项目；VIEWER 可全局读取业务数据，但不能写入业务数据；USER 只读取其加入的项目。
- 每个项目恰有一个有效 PM。PM 管项目、成员、模块、阶段目标和周报。模块负责人管理自己模块下的节点和任务；节点负责人管理自己的节点和任务；任务执行人管理自己的任务状态和工时。实际授权必须由后端校验，前端按钮可见性只是提示。
- 归档项目保留可读历史；除恢复项目外，项目业务写操作被拒绝。改派目标必须是本项目有效且非 VIEWER 的成员。
- 文档与附件分四层：项目级由 PM 上传，模块级由模块负责人上传，节点级由节点负责人/模块负责人/PM 上传，任务级由执行人/节点负责人/模块负责人/PM 上传；删除限上传者本人或对应层级负责人。下载统一按所属项目校验，VIEWER 可下载不可上传，归档项目只读。删除为软删除，磁盘文件保留以维持痕迹；删除模块时其模块文档一并软删除。项目文档页「全部文件」汇总四层文件并标明归属。
- 任务不要求录工时：用状态（待完成/进行中/已完成）标记进度。逐日工时的表与接口保留、历史数据不动，但界面不再提供录入口。任务另有「任务情况」「处理方式」两个说明字段、追加式进展记录（`task_notes`，带作者与时间，可当备注）和附件；任务可编辑（名称/描述/情况/处理方式/改派/截止日/计划人天）和删除（同时删除记录与工时、附件软删除）。
- 任务列表提供快捷状态按钮（待完成→开始、进行中→完成、已完成→重新开始），提交时带 `expectedStatus`，状态已被他人改变会返回 409 并刷新列表。
- 文档内容类型由服务端按扩展名推导，不采用客户端声明；仅 PDF 与图片内联展示，其余强制下载，并带 `X-Content-Type-Options: nosniff`。
- 在线预览在浏览器内完成，不新增服务端接口、不引入转换服务：PDF/图片用浏览器原生能力，txt/md/csv 用只读文本，docx 用 docx-preview，xlsx 用 read-excel-file；`doc/xls/ppt` 等旧格式与压缩包只提供下载。
- 预览的安全边界：SVG 不提供预览（blob: 文档继承应用源，脚本可在新标签页执行）；docx 渲染必须保持 `renderAltChunks/renderChanges/renderComments: false`（altChunk 会把内嵌 HTML 渲染成同源 iframe），渲染后还要清洗超链接（只留 http(s)/mailto/锚点）。PDF 走浏览器内置阅读器（独立扩展源），未加 iframe `sandbox`，原因与待验证项见 PLAN。改动这两个库的选项前先看 PLAN 里的 PoC 记录。
- 节点交付物清单的写入权限与节点级附件一致（节点负责人/模块负责人/PM，VIEWER 与归档项目只读）；关联文件必须是同一节点未删除的附件，但**只在真的改关联时才校验**——文件被软删除后，清单仍可只改状态（否则关闭节点上文件下拉禁用会让该项永远标不成“已提供”）。已完成/已取消节点只能改交付物状态，新增、改名、改派、换文件、改备注、改排序、删除一律 409。完全无变化的更新直接返回、不写日志；清单是物理删除，不删除关联文件。
- 项目进度按未取消节点的进度直接平均；模块进度同样对本模块未取消节点直接平均。节点进度由 `ProgressRules` 按任务完成率派生（无任务时按状态估算，DONE 固定 100，未全部完成最高 99），不提供手动填写。延期、临近、受阻分别保留标记。周报按周一至周日归周。

## 验证与已知边界

- 基本检查：`mvn -gs .mvn/settings.xml -s .mvn/settings.xml -f backend/pom.xml test`（当前 29 个单测）；`npm --prefix frontend run lint`、`typecheck`、`build`。浏览器路径测试在 `frontend/tests/roles.spec.ts`。
- `PLAN.md` 中早期“47 个接口”“11 个页面”“10/11 张表”是当时版本的验证快照。当前接口为 63 个、表 15 张（含 `flyway_schema_history`），页面已新增模块工作页与项目文档页；不能把早期数字当成现状。
- 当前工作站此前未运行 Docker Compose 的真实构建；部署仍需在具备 Docker 的目标环境验证。
- 文件存储当前用本地磁盘（`app.storage.root` 默认 `./data/files`，容器内 `/app/data/files` 挂 `file-data` 卷）。未接入 MinIO/对象存储：存储层已抽成 `FileStorage` 接口，切换时只加实现并改配置。单文件上限 50MB，Nginx 侧 `client_max_body_size 60m`，后端 `spring.servlet.multipart` 与 `app.storage.max-file-bytes` 同步。
- Git 远程：`https://github.com/txy1785/proj-timeline.git`。分支模型：`main` 版本上线、`develop` 开发主线、`feature/<描述>` 功能分支从 `develop` 拉出；功能代码必须经独立审核（干净会话/子代理）后合并 `develop`，发布从 `develop` 合并 `main` 并打 tag。GitHub 默认分支为 `main`，远程仅保留 `main` 与 `develop`（旧 `master` 已删除）。提交前先 `git status` 核对未被 .gitignore 遗漏的文件。
- 曾在本机启动 Vite 5173 与后端 8080 供用户试用；进程是否仍运行应实时检查，不能依据本文假定。最近浏览器停在登录页，可能只是会话过期。
