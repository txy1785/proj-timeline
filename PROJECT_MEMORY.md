# 项目记忆

本文件记录当前已确认的产品决定、代码现状和开发上下文。每次需求改变后更新对应条目；实施过程和旧版本验证记录保留在 [PLAN.md](PLAN.md)，不要把历史记录当成当前契约。

## 项目定位

- 名称：项目时间线。面向约 10 人以内团队的自托管进度管理系统。
- 业务主线：项目 → 功能模块 → 节点 → 任务；项目还有独立的阶段性目标、成员、周报。逐日工时挂在任务下。
- `docs/需求文档与产品设计方案.md` 说明产品需求；`docs/接口设计.md` 说明 HTTP 路径、权限和统计口径；`docs/database.sql` 是新库 DDL 参考；实际应用升级由 `backend/src/main/resources/db/migration/` 中的 Flyway 脚本完成。
- `prototype/` 是早期视觉参考，界面实现以 `frontend/src/` 为准。

## 用户已经确认的关键决定

1. 项目开始日期必填，项目完成日期可以不填。项目完成日期未知时仍可创建和维护项目。
2. 阶段性目标直接属于**项目**，包含名称、可选说明和一个目标日期；一个项目可以有多个目标。目标日期不得早于项目开始日期；项目完成日期存在时也不得晚于该日期。
3. 模块是**功能划分**，包含名称、可选描述和一位负责人。模块没有阶段开始/完成日期，也不承载项目阶段性目标。Flyway V2 曾临时加入模块日期，V3 已移除；不得依据 V2 恢复这一设计。
4. 模块负责人进入自己的模块，应能查看节点并填写新节点的名称、说明、计划起止日期和负责人。模块卡片与时间线模块名称均通向模块工作页。新建节点默认继承模块负责人，可改派给项目内有效且非 VIEWER 的成员。
5. “本周实际人天”按每日工时明细的 `work_date` 在自然周内汇总。更正未发布周的工时会改变实时周报；已发布周报保留发布时的 JSON 快照。
6. 节点进度不手填：节点进度 = 已完成任务数 ÷ 任务总数；没有任务的节点按状态估算（未开始 0、进行中 50、已完成 100），DONE 固定 100，未全部完成时最高显示 99。完成节点前必须先把该节点任务全部完成；已完成/已取消节点不能新增任务，任务状态也不能再变更；取消节点下未完成的任务不计入人员负载与周报计划人天。原手动进度接口已移除；`nodes.progress` 列只保留历史值，不再读写。

## 当前实现定位

| 内容 | 当前位置 |
|---|---|
| 项目、成员、模块、阶段目标与时间线业务 | `backend/src/main/java/com/projtimeline/project/` |
| 节点、任务、逐日工时 | `backend/src/main/java/com/projtimeline/work/` |
| 进度派生与聚合口径 | `backend/src/main/java/com/projtimeline/common/ProgressRules.java`（节点）、`project/ProjectService.java`（模块/项目聚合） |
| 项目权限 | `backend/src/main/java/com/projtimeline/security/PermissionService.java` |
| 项目设置与阶段目标编辑 | `frontend/src/views/ProjectSettingsView.vue` |
| 模块卡片与模块工作页 | `frontend/src/views/ModulesView.vue`、`ModuleDetailView.vue` |
| 项目时间线、节点详情 | `frontend/src/views/TimelineView.vue`、`NodeDetailView.vue` |
| 路由、会话、HTTP 客户端 | `frontend/src/router.ts`、`stores/auth.ts`、`api/client.ts` |
| 项目、模块、节点与任务的文档附件 | `backend/src/main/java/com/projtimeline/file/`、`frontend/src/components/AttachmentPanel.vue`、`AttachmentPreview.vue`、`ProjectDocsView.vue`、`utils/attachment.ts` |

- 后端：Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios；使用 npm 和 `package-lock.json`。文件预览额外依赖 `docx-preview` 与 `read-excel-file`（按需动态加载，不进入主包）。
- 当前 Flyway 版本为 V7。V1 建表，V2 将项目开始日期设为必填并过渡性增加模块日期，V3 建立 `project_stage_targets` 并移除模块日期，V4 建立 `attachments` 文档附件表，V5 只修正 `owner_type` 注释（V4 已应用到环境，不得再改写），V6 建立 `task_notes` 并让 `attachments.owner_type` 支持 TASK，V7 只把 `nodes.progress` 列注释改为「已弃用」（进度改为任务完成率派生，保留列与历史值，不删列）。已有迁移文件不能改写；新变更追加 V8 及以后版本。
- 当前 OpenAPI 有 58 个 `/api` 操作。前端生成类型在 `frontend/src/api/schema.d.ts`，后端运行时用 `npm --prefix frontend run api:types` 更新（脚本默认 8080，本机后端在 58080 时需手动指定 URL）。
- 认证为 8 小时 JWT，仅保存用户 ID；每次请求重新检查账号启用状态与角色。浏览器会话存于 `sessionStorage`。
- 日期判定使用注入的 `Clock`，默认业务时区 `Asia/Shanghai`。延期和临近到期由服务端实时计算。
- 演示数据只在显式执行 `docs/demo_seed.sql` 时导入；不属于 Flyway 正式迁移。根目录 `.env` 和本机数据库凭据不得写入文档或提交。

## 权限和数据边界

- 全局角色：`ADMIN`、`USER`、`VIEWER`。ADMIN 可管理全部项目；VIEWER 可全局读取业务数据，但不能写入业务数据；USER 只读取其加入的项目。
- 每个项目恰有一个有效 PM。PM 管项目、成员、模块、阶段目标和周报。模块负责人管理自己模块下的节点和任务；节点负责人管理自己的节点和任务；任务执行人管理自己的任务状态和工时。实际授权必须由后端校验，前端按钮可见性只是提示。
- 归档项目保留可读历史；除恢复项目外，项目业务写操作被拒绝。改派目标必须是本项目有效且非 VIEWER 的成员。
- 文档与附件分四层：项目级由 PM 上传，模块级由模块负责人上传，节点级由节点负责人/模块负责人/PM 上传，任务级由执行人/节点负责人/模块负责人/PM 上传；删除限上传者本人或对应层级负责人。下载统一按所属项目校验，VIEWER 可下载不可上传，归档项目只读。删除为软删除，磁盘文件保留以维持痕迹；删除模块时其模块文档一并软删除。项目文档页「全部文件」汇总四层文件并标明归属。
- 任务不要求录工时：用状态（待完成/进行中/已完成）标记进度。逐日工时的表与接口保留、历史数据不动，但界面不再提供录入口。任务另有追加式进展记录（`task_notes`，带作者与时间，可当备注）和附件。
- 任务列表提供快捷状态按钮（待完成→开始、进行中→完成、已完成→重新开始），提交时带 `expectedStatus`，状态已被他人改变会返回 409 并刷新列表。
- 文档内容类型由服务端按扩展名推导，不采用客户端声明；仅 PDF 与图片内联展示，其余强制下载，并带 `X-Content-Type-Options: nosniff`。
- 在线预览在浏览器内完成，不新增服务端接口、不引入转换服务：PDF/图片用浏览器原生能力，txt/md/csv 用只读文本，docx 用 docx-preview，xlsx 用 read-excel-file；`doc/xls/ppt` 等旧格式与压缩包只提供下载。
- 预览的安全边界：SVG 不提供预览（blob: 文档继承应用源，脚本可在新标签页执行）；docx 渲染必须保持 `renderAltChunks/renderChanges/renderComments: false`（altChunk 会把内嵌 HTML 渲染成同源 iframe），渲染后还要清洗超链接（只留 http(s)/mailto/锚点）。PDF 走浏览器内置阅读器（独立扩展源），未加 iframe `sandbox`，原因与待验证项见 PLAN。改动这两个库的选项前先看 PLAN 里的 PoC 记录。
- 项目进度按未取消节点的进度直接平均；模块进度同样对本模块未取消节点直接平均。节点进度由 `ProgressRules` 按任务完成率派生（无任务时按状态估算，DONE 固定 100，未全部完成最高 99），不提供手动填写。延期、临近、受阻分别保留标记。周报按周一至周日归周。

## 验证与已知边界

- 基本检查：`mvn -gs .mvn/settings.xml -s .mvn/settings.xml -f backend/pom.xml test`；`npm --prefix frontend run lint`、`typecheck`、`build`。浏览器路径测试在 `frontend/tests/roles.spec.ts`。
- `PLAN.md` 中早期“47 个接口”“11 个页面”“10/11 张表”是当时版本的验证快照。当前接口为 58 个，页面已新增模块工作页与项目文档页；不能把早期数字当成现状。
- 当前工作站此前未运行 Docker Compose 的真实构建；部署仍需在具备 Docker 的目标环境验证。
- 文件存储当前用本地磁盘（`app.storage.root` 默认 `./data/files`，容器内 `/app/data/files` 挂 `file-data` 卷）。未接入 MinIO/对象存储：存储层已抽成 `FileStorage` 接口，切换时只加实现并改配置。单文件上限 50MB，Nginx 侧 `client_max_body_size 60m`，后端 `spring.servlet.multipart` 与 `app.storage.max-file-bytes` 同步。
- Git 远程：`https://github.com/txy1785/proj-timeline.git`。分支模型：`main` 版本上线、`develop` 开发主线、`feature/<描述>` 功能分支从 `develop` 拉出；功能代码必须经独立审核（干净会话/子代理）后合并 `develop`，发布从 `develop` 合并 `main` 并打 tag。GitHub 默认分支为 `main`，远程仅保留 `main` 与 `develop`（旧 `master` 已删除）。提交前先 `git status` 核对未被 .gitignore 遗漏的文件。
- 曾在本机启动 Vite 5173 与后端 8080 供用户试用；进程是否仍运行应实时检查，不能依据本文假定。最近浏览器停在登录页，可能只是会话过期。
