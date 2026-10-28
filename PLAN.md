# 项目时间线 v1 实施记录

> 状态：M0–M4 已实施并通过当前工作站可执行的门禁。Docker CLI 未安装，容器部署文件完成静态检查，仍需在装有 Docker 的目标机执行一次 `docker compose build`。

## 固定方案与版本记录

- 工程按 `backend/`、`frontend/` 建立，保留 `docs/` 与 `prototype/`。
- 后端使用 Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 计划指定的 Spring Boot 3.5.17 在 2026-10-18 的 Maven Central 与阿里云镜像均不存在；采用同一 3.5 维护线中可取得的最新版本 3.5.16。MyBatis-Plus 保持 3.5.17。
- 前端使用 Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios、npm 锁文件。
- 认证为 8 小时 HS256 JWT，仅包含用户 ID；过滤器每次请求重新读取账号、全局角色和启用状态。
- 业务日期统一通过 `Clock` 与 `Asia/Shanghai` 计算。Flyway 只包含正式 DDL；演示数据保留为显式导入脚本。

## 阶段状态

| 阶段 | 状态 | 已完成内容 |
|---|---|---|
| M0 | 完成 | 初始化 Git 和忽略规则；真实 MySQL 8.0.42 导入正式 DDL 与种子；验证外键、唯一 PM、日期检查及三组周报数字 |
| M1 | 完成 | 后端/前端基础、Flyway、统一响应与异常、OpenAPI、健康检查、管理员一次性初始化、登录、项目/成员/模块/节点/时间线 |
| M2 | 完成 | 任务、状态、改派、逐日工时、任务累计、跨项目负载、任务看板与负载页 |
| M3 | 完成 | 受阻、延期原因、审计、通知去重、周报实时预览/草稿/说明/发布/历史快照 |
| M4 | 完成 | 管理员账号、归档恢复、11 个页面、响应式样式、Nginx、Docker Compose、环境示例与运维文档 |

## 接口和实现结果

- Springdoc 真实运行结果为 50 个 `/api` 操作；生成类型已写入 `frontend/src/api/schema.d.ts`，脚本为 `npm run api:types`。
- 所有响应使用 `ApiResponse<T>`，分页使用 `PageResponse<T>`；分页默认 20、最大 100。
- 项目写权限集中在 `PermissionService`；归档项目只允许读取和恢复。
- 项目创建及 PM 成员、PM 转交、节点审计、通知、周报发布均在事务内完成；PM 转交和发布使用行锁。
- 延期、临近、项目进度、任务实际人天和未发布周报实时计算；已发布周报保存 JSON 快照。
- 前端使用 `sessionStorage` 保存会话，路由守卫限制未登录和 ADMIN 页面；时间线使用 CSS Grid，无甘特图依赖。

## 实际验证记录

### M0 数据库

在本机 MySQL 8.0.42 的独立临时测试库中从空库执行正式 DDL 与 `docs/demo_seed.sql`，验证后删除测试库：

```text
tables=10
users=7
week_2025_12_15_actual=4.5
week_2025_12_15_plan=17.0
unique_pm_constraint=PASS
foreign_key_constraint=PASS
date_constraint=PASS
```

### 后端与真实 API

执行命令：

```powershell
$env:JAVA_HOME='E:\environment\jdk_v\jdk 17'
mvn -gs .mvn/settings.xml -s .mvn/settings.xml -f backend/pom.xml test
mvn -gs .mvn/settings.xml -s .mvn/settings.xml -f backend/pom.xml package
```

结果：9 个单元测试通过。固定 `Clock` 覆盖到期当天、次日、未来三天边界及周一至周日归周。

在临时 MySQL 与打包后的 jar 上执行管理员、PM、成员、VIEWER 主路径：

```text
health=UP; tables=11; projectId=1; nodeId=1; taskId=1
actualDaysBeforePublish=1.5; correctedActualDays=2.0
publishedSnapshotStable=true
viewerWriteRejected=true
archivedWriteRejected=true
disabledTokenRejected=true
bootstrap_retry=REJECTED
bootstrap_command_exits=true
openapi_operations=47; schema=GENERATED
```

该流程覆盖空库 Flyway、首位管理员、登录、账号、项目、成员、模块、节点、默认任务负责人、工时、负载、通知、周报幂等与快照、归档恢复以及停用账号旧 JWT 失效。

### 前端

执行命令与结果：

```text
npm --prefix frontend run lint       PASS
npm --prefix frontend run typecheck  PASS
npm --prefix frontend run build      PASS
npm --prefix frontend run test:e2e -- --reporter=line --workers=1
6 passed
```

Playwright 覆盖匿名跳转、管理员账号页、PM、成员、VIEWER 项目页，以及 VIEWER 管理路由拒绝。

## 失败与修复

- Maven 默认本地仓库位于当前沙箱不可写目录，新增 `.mvn/settings.xml` 将仓库定向到项目 `.m2`。
- 首次 Maven 打包缺少插件且沙箱禁止联网，获准从 Maven Central 下载后打包通过。
- 延期原因首次写入时旧值可能为 `null`，将不可空的 `Map.of` 改为可空映射。
- 周报异常查询原先使用数据库 `CURRENT_DATE`，改为由注入的 `Clock` 生成 `today` 查询参数。
- Playwright 在受限沙箱内无法访问自身启动的本地 Vite 端口；在获准的本机执行环境中 6 个测试全部通过。

## 剩余风险与目标机门禁

- 当前工作站没有 Docker CLI，尚未实际运行 `docker compose build`；目标机部署前必须执行构建、健康检查及一次登录回归。
- Vite 报告主入口压缩前约 1.09 MB。v1 功能和构建不受影响；后续可按路由拆分 Element Plus 以缩小首屏包。
- 真实 MySQL API 验收以临时脚本执行，结果已记录，但尚未纳入 CI。接入 CI 时应配置明确命名的本机测试库，并保留生产式库名和远程地址清库保护。

## 变更记录：项目阶段性目标

- Flyway V2 先完成项目开始日期必填和兼容回填；V3 根据需求澄清移除模块阶段日期，新增直接属于项目的 `project_stage_targets`。
- 项目设置支持阶段性目标的创建、编辑和删除；时间线展示项目阶段目标。模块恢复为功能拆分，只保留名称、描述和负责人。
- 新增 3 个写接口，接口总数为 50。项目日期范围调整不得排除已有阶段性目标。
- 2026-10-19 验证：后端 9 个单元测试通过；前端 lint、typecheck、build 通过；现有 `proj_timeline` 成功升级至 v3。真实 API 验证目标日期边界、创建、编辑、时间线返回、删除以及模块无阶段日期均通过。
- 模块负责人工作入口：新增 `/projects/:projectId/modules/:moduleId` 模块工作页，模块卡片和时间线模块名称均可进入；负责人可填写节点名称、说明、计划起止日期和负责人。前端 lint、typecheck、build 通过，并完成右侧页面可见验证。
- 首次重打包时运行中的后端占用 jar，Spring Boot repackage 无法重命名产物；停止旧进程后重新打包成功，并完成服务重启与健康检查。

## 项目记忆与开发规则整理（2026-10-19）

- 新增 `PROJECT_MEMORY.md`，汇总用户确认的实体关系、日期与工时口径、当前实现位置、权限边界及已知验证限制。
- 新增根目录 `AGENTS.md`，规定改动前核对契约、Flyway 迁移追加、后端权限校验、OpenAPI 类型同步、最小有效验证和本地服务维护方式。
- `README.md` 增加这两个文件的入口。逐项核对文档引用和文件路径；本次只修改文档，未重新执行构建或更改运行中的服务。

## Git 仓库初始化与首次推送（2026-10-19）

- 仓库此前已 `git init` 但无提交；核对 `.gitignore` 已覆盖 `frontend/node_modules/`、`backend/target/`、`.env`、`.idea/`、`.m2/`、`frontend/dist/`、Playwright 产物与本地配置。
- 提交前检查 `.mvn/settings.xml`（仅 Maven 镜像，无凭据）、`.env.example`（仅占位符）、`deploy/` 与 `scripts/`，未发现需排除的敏感内容。
- 分支重命名为 `main`，创建首个提交：98 个文件、13,479 行；移除调研阶段遗留的测试文件 `research/sandbox-test.txt`。
- 关联远程 `https://github.com/txy1785/proj-timeline.git` 并推送成功；本地 `HEAD` 与 `origin/main` 均为 `6557ca6`，工作树干净。
- 推送认证使用 Git Credential Manager（`credential.helper=manager`）。本次未修改代码与配置，未重新执行构建或测试。

## 复用库与防重复编码（2026-10-19）

- 新增 `docs/复用方法与设计模式.md`：登记现有后端通用件（`ApiResponse`/`PageResponse`/`BusinessException`/`BusinessCalendar`/`RequestValues`/`GlobalExceptionHandler`/`PermissionService`/`CurrentUser`/`JwtService`/`JwtAuthenticationFilter`/`AppProperties`/`AdminBootstrapRunner`/`DataAccess`/`UserMapper`）、业务口径（实体层级、延期临近、进度聚合、负责人继承、通知触发、周报快照、权限矩阵）、前端通用件（`client.ts` 请求封装、`types.ts`、`schema.d.ts`、`auth` store、路由守卫、样式 token、视图模式）与测试约定，并附登记模板与维护清单。
- `AGENTS.md` 新增「复用库与防重复编码」规则：写码前先查；新建可复用项必须登记；修改同步更新；废弃标注替代方案；禁止复制粘贴平行实现。
- `README.md` 增加复用库入口。
- 本次只新增文档与规则，未修改代码；未重新执行构建或测试。

## Git 分支与提交流程（2026-10-19）

- 定义分支模型：`master` 版本上线；`develop` 开发主线；`feature/<描述>` 功能分支从 `develop` 拉出；`fix/` 用于紧急修复。文档/规范类变更可直接提交 `develop`，功能代码必须走 `feature/` 分支。
- 本地 `main` 更名为 `master`，新建 `develop` 并推送；远程因 `main` 仍为 GitHub 默认分支而拒绝删除，待默认分支切换为 `master` 后删除。
- 远程 `main` 清理完成：通过 GitHub API 将默认分支切换为 `master` 后删除 `main`；当前远程仅 `master`（`ce5233c`）与 `develop`（`0a5d11d`）。
- `AGENTS.md` 新增「Git 分支与提交流程」：PR 由管理者在 GitHub 创建（base `develop`）；提交者 AI 必须用独立会话/子代理（review 模式）审核，含 6 项审核清单，通过后合并 `develop`；发布从 `develop` 合并 `master` 并打 `vX.Y.Z` tag。
- `README.md` 增加分支协作说明；`PROJECT_MEMORY.md` 更新分支模型。
- 本次只做分支与规则文档变更，未改业务代码；未重新执行构建或测试。

## 分支命名统一为 main + develop（2026-10-19）

- proj-timeline 上线分支 `master` 更名为 `main`（本地与 GitHub 远程），GitHub 默认分支切换为 `main`，旧 `master` 已删除；远程现仅保留 `main` 与 `develop`。
- 文档同步：`AGENTS.md`、`README.md`、`PROJECT_MEMORY.md` 中的上线分支名改为 `main`；分支模型与审核流程不变。
- candid-eval 侧：`github` 远程由 SSH 改为 HTTPS（本机 SSH 链路不稳定）；`main` 已合并回 `develop`（合并提交 `94efa20`），Gitee/GitHub 双远程同步。
- 本次只改文档与分支，未改业务代码；未重新执行构建或测试。

## 项目与模块文档、附件（2026-10-19，feature/file-upload）

- 选型结论：先本地磁盘 + 存储抽象层。理由是本项目权限矩阵要求每次下载按所属项目实时校验，对象存储预签名 URL 会把授权转交给 URL；且当前 Compose 仅 db/backend/frontend 三服务，引入 MinIO 的容器、密钥、CORS 与内外网地址成本对 10 人自团队不划算。后期需要时新增 `MinioStorage` 实现并切换 `app.storage` 配置。
- 数据：Flyway `V4__attachments.sql` 新建 `attachments`，多态归属 `owner_type`（PROJECT/MODULE，预留 NODE/TASK）+ `owner_id`，`category` 走字典编码，`version` 同名文件自增，`deleted` 软删除保留痕迹。
- 分类字典集中在 `DocumentCatalog`：项目级 7 组 28 个分类（立项计划/需求/设计规范/测试质量/发布运维/验收收尾/管理过程），模块级 1 组 8 个分类（模块设计、流程图、接口说明、开发记录、评审、联调自测、交付物）。参考 GB/T 8567-2006 的文档主线并归并到 10 人团队可用粒度。
- 接口 5 个：分类字典、列表、上传（multipart）、流式下载、软删除；接口总数 50 → 55。
- 权限：项目级上传删除限 PM/ADMIN，模块级限模块负责人/PM/ADMIN；读统一 `requireProjectRead`，VIEWER 可下不可传，归档项目只读。
- 前端：新增 `ProjectDocsView`（`/projects/:id/docs`）与可复用 `AttachmentPanel`；模块工作页嵌入同一面板（关闭分类导航）。契约新增 `upload()`/`download()`：blob 下载携带 Authorization，避免 token 进 URL；上传单独放宽超时到 5 分钟。
- 部署：`deploy/nginx.conf` 增 `client_max_body_size 60m` 与 `proxy_request_buffering off`；Compose 新增 `file-data` 卷挂 `/app/data/files`；后端镜像预建该目录并 chown 给 `timeline` 用户；`.gitignore` 忽略本地 `data/`。
- 验证：后端 `mvn test` 9 个用例通过；前端 `lint`、`typecheck`、`build` 通过。未验证：真实 MySQL 上 V4 迁移与上传下载全链路（需可用的本机库），Docker 构建仍需目标机执行。

### 独立审核后的修复（同日）

- 审核结论：有条件通过；阻塞项是下载接口的同源存储型 XSS——`content_type` 直接取客户端 multipart 声明，且 `inline` 判定覆盖 `text/*` 与全部 `image/*`，低权限账号可上传声明为 `text/html` 的文件诱导管理员打开。
- 修复：新增 `MediaTypes` 由服务端按扩展名推导类型；仅 pdf/png/jpg/jpeg/gif/webp 内联，其余强制 `attachment` + `octet-stream`；下载响应补 `X-Content-Type-Options: nosniff`、`Cache-Control: private, no-store`；Nginx 全站补 nosniff 与 `X-Frame-Options`。
- 修复：上传失败清理只包住 `db.insert`，清理异常记录日志而不掩盖原始异常；`put` 按实际写入字节数校验，不符即删除并返回 4000。
- 修复：`V4` 补 `uk_attachment_version` 唯一键、`uploader_id` 外键与 `SET NAMES`，缓解并发同名上传的版本竞态。
- 修复：删除模块时同步软删除其模块级文档（原先留下不可见却可按 id 下载的孤儿附件）；删除权限与上传口径统一为「上传者本人或对应管理者」。
- 修复：`BusinessException.internal()` 工厂替代直接构造；前端删除按钮按行判断上传者、`download()` 延迟释放 blob URL、错误提示解析 blob 中的 JSON、上传前预检 50MB、只读场景不再拉分类字典。
- 新增单测 `LocalDiskStorageTest` / `MediaTypesTest` / `StoragePropertiesTest`，后端用例 9 → 17。
- 端到端复验：独立测试库 `proj_timeline_upload_test` 跑通 V1–V4 迁移、上传、版本递增、列表、下载内容一致、模块级上传、未知分类与非法 ownerType 拒绝、软删除清空列表，以及 VIEWER 可读不可写、非成员不可读、无 token 401，共 16 项用例全通过；验证后已删除测试库与临时文件，未触碰开发库。

### 第二轮复审后的修复（同日）

- 复审指出唯一键与版本算法口径冲突：`MAX(version)` 只统计 `deleted=0`，删除后重传同名标题会永久撞 `uk_attachment_version` 并返回 4090。修复为版本号对软删记录同样占位（去掉 `deleted=0`），删除后重传得到更高版本号。
- 上传失败清理范围扩到「算版本 → 落库」整段，避免 `db.count`/`permissions.current()` 抛错时磁盘文件永久残留；唯一键冲突转换为「同名文件正在上传，请稍后重试」。
- `LocalDiskStorage` 的 IOException 分支同时删除半写文件；`.gitignore` 补 `/backend/data/`；前端删除按钮按「未归档 &&（有管理权 || 本人上传）」判断。
- 复核确认 `V4` 从未在开发库执行（`flyway_schema_history` 仅 V1–V3），就地修订不存在 checksum 风险。
- 回归用例（临时脚本，未入库）：上传 v1 → 软删除 → 重传同名得 v2 → 列表只显示存量版本 → 下载一致 → 旧 id 返回 404，全部通过。删除后重传与并发同名仍缺自动化回归，列入后续待补。

## 节点级文件与全项目文件汇总（2026-10-19，feature/node-attachments）

- 需求确认：保留项目级「总文件」展示，同时模块下的**功能节点**可以挂自己的文件（节点设计说明、流程图、接口说明、自测记录、截图、交付物）。
- 归属扩展：`ownerType` 增加 `NODE`（`TASK` 仍预留）。`DocumentCatalog` 新增节点级分类组「节点产出」7 项。
- 权限：节点级上传/删除走 `requireNodeManager`（节点负责人、模块负责人、PM/ADMIN），与现有节点写权限口径一致；读取仍按所属项目校验。
- 新接口 `GET /projects/{id}/attachments`：汇总项目级 + 模块级 + 节点级文件，返回 `ownerType`、`ownerId`、`ownerName`（项目名/模块名/节点名），接口总数 55 → 56。
- 前端：`ProjectDocsView` 改为两个页签——「全部文件」（汇总表，带归属列与来源筛选，可下载）和「项目级文档」（原有分类上传）；`NodeDetailView` 增加文档面板，`canManage` 按 PM/模块负责人/节点负责人计算。
- 节点详情响应补充 `moduleOwnerId` 与 `projectStatus`，供前端判断节点文件管理权与归档只读（纯新增字段，不影响既有消费方）。
- 备注：系统目前不支持删除节点，因此节点级文件无需清理逻辑；删除模块时仍会软删模块级文件。

### 独立审核后的修复（同日）

- 审核发现阻塞级问题：`V4` 只改了注释，但**开发库已执行过 V4**（`flyway_schema_history` 有版本 4、checksum -2066996562，说明用户已用新 jar 重启），改注释会使下次启动 checksum 校验失败。因此把 `V4` 恢复为已发布版本原样，注释修正改由 **V5**（`ALTER TABLE ... MODIFY owner_type ... COMMENT`）完成。
- 提交了「切换页签刷新汇总列表」的修复（原先只在工作区，未随分支提交）。
- `NodeDetailView` 拆分 `loadNode()`/`loadMembers()`：状态、进度、受阻、建任务、录工时等操作只刷新节点，不再重复请求成员列表。
- 抽取 `frontend/src/utils/attachment.ts`（`formatBytes`、`downloadAttachment`）并在复用库登记，消除 `AttachmentPanel` 与 `ProjectDocsView` 的重复实现；后端把列表列清单抽成 `BASE_COLUMNS`，列表与汇总共用，避免加列时漂移。
- 汇总查询补 `, a.id DESC` 稳定排序；前端归属名补 `—` 兜底；「项目级文档」页签改为 `lazy`，默认页签不再多发请求。
- 文档：接口清单 #56 路径统一为 `{projectId}`、§3.6 补 `moduleOwnerId`/`projectStatus`、§5 补齐「项目文档」与节点详情接口映射、`PROJECT_MEMORY` 同步为 56 个接口与 Flyway V5。

## 任务：状态化、记录与附件（2026-10-20，feature/task-notes-attachments）

- 修复缺陷：节点详情「新增任务」弹窗未重置表单，第二次打开会残留上一次的任务名称；改为打开时重置、提交成功后清空，并补任务名称必填校验。
- 口径变更：任务不再要求录工时，直接用状态（待完成/进行中/已完成）标记进度。**逐日工时的表 `task_work_logs` 与 4 个工时接口保留、历史数据不动**，仅移除节点页的录工时入口；周报「本周实际人天」今后来自历史数据，不再新增。
- 新增 `task_notes`（Flyway V6）：追加式进展记录，带作者与创建时间，不可编辑、可删除（作者本人或管理者），最长 2000 字。接口 3 个：`GET /tasks/{id}/notes`、`POST /tasks/{id}/notes`、`DELETE /task-notes/{id}`，接口总数 56 → 59。
- 任务附件：`attachments.owner_type` 增加 `TASK`（V6 同时修正列注释），任务级上传/删除走 `requireTaskManager(id, true)`（执行人可用），项目文档页「全部文件」新增任务级归属名与筛选。
- 任务列表返回 `noteCount`；节点详情任务表改为显示状态标签、记录条数与「详情」入口，并提供任务详情弹窗（状态切换 + 记录 + 附件）。
- 实现取舍：任务记录不写 `node_logs`——该表 `action` 是 ENUM，新增值需再改一次已发布表结构，而记录行本身已带作者与时间，足够追溯。
- 验证：后端 17 个单测通过；前端 lint/typecheck/build 通过；测试库（V1–V6）7 组用例通过——新任务记录数为 0、追加与读回、列表 `noteCount`、状态三档切换、任务附件上传与汇总归属名、删除记录、VIEWER 可读不可写。

## 文件在线查看（2026-10-20，feature/file-preview）

- 需求确认：上传的文件要能在线查看，范围是 PDF、图片、TXT/Markdown/CSV，另加 Word(docx) 与 Excel(xlsx)；交互用页面内弹窗。
- 方案：预览全部在浏览器完成，**不新增服务端接口、不引入 LibreOffice 等转换服务**。前端用现有的下载接口取 Blob（带 Authorization），按扩展名渲染。好处是服务端对非 PDF/图片继续返回 `attachment`，不扩大上次审核收敛掉的内联渲染面。
- 实现：`utils/attachment.ts` 增加 `previewKind`（pdf/image/text/docx/xlsx/none）与 `fetchAttachmentBlob`（按扩展名补正确 MIME）；新增 `components/AttachmentPreview.vue`，docx 用 `docx-preview`、xlsx 用 `read-excel-file`，两者均 `await import()` 动态加载，主包体积不受影响（docx 单独 chunk 约 173KB）。
- 依赖取舍：最初装了 `xlsx`(SheetJS)，npm audit 报 2 条 high 且**无修复版本**（原型污染、ReDoS），改用 `read-excel-file`——API 为一次读全部工作表，返回 `[{sheet,data}]`，正好满足按 sheet 分页展示；最终 `npm audit` 为 0 漏洞。
- 入口：附件列表与项目文档「全部文件」都提供「预览」按钮，仅对可预览格式显示；弹窗底部保留「下载原文件」。
- 限制：最多展示前 200 行、前 5 个工作表；docx/xlsx 超过 20MB 只提示下载；不在白名单内的格式不显示预览按钮。
- 验证：lint/typecheck/build 通过；用 Playwright + Chromium 在临时实例上真实跑通——上传样例 docx/xlsx，打开项目文档页点「预览」，docx 渲染出正文文字、xlsx 渲染出表头与单元格（含数字 42），无 console 错误或页面异常。测试库与临时前后端实例验证后已删除。

### 独立审核后的修复（同日）

- 审核用 Playwright PoC 证明 `docx-preview` 默认 `renderAltChunks: true` 会把文档内嵌的 HTML 部件渲染成**无 sandbox 的同源 iframe**，上传者即可在应用源执行脚本（存储型 XSS）。修复：渲染参数改为 `renderAltChunks/renderChanges/renderComments: false`、`useBase64URL: true`（同时避免库内部 blob URL 泄漏），渲染后再清洗超链接（只保留 http(s)/mailto/锚点，外链加 `target=_blank rel=noopener noreferrer`）。
- 同族问题：docx 里的 `javascript:` 超链接会被原样写进 `href`，点击即执行；已由上面的链接清洗覆盖。
- SVG 重新内联的风险：blob: 文档继承应用源，右键「新标签页打开」即可执行 SVG 里的脚本读走 JWT。修复：预览白名单剔除 svg（与后端 `MediaTypes.INLINE_TYPES` 对齐），SVG 只下载。
- 快速切换文件的竞态：加 `loadToken` 序号丢弃过期响应、`onBeforeUnmount` 清理 blob URL、docx 渲染前后比对 token。
- 依赖锁精确版本（`docx-preview@0.4.1`、`read-excel-file@9.3.12`），避免小版本改默认安全选项。
- 预览状态抽成 `composables/useAttachmentPreview.ts`，两个调用方共用；文档同步：不支持 `log/json` 预览（不在上传白名单）、不可预览格式不显示按钮。
- 复验（Playwright + 构造攻击样本）：altChunk 文档渲染后 iframe 数 0、脚本未执行、内嵌内容不显示；`javascript:` 链接 href 被移除且点击不执行；SVG 行无预览按钮；正常 docx 仍正确渲染；全程无 console 错误。
- 补充复验：含图片的 docx 在 `useBase64URL: true` 下渲染为 `data:image/png;base64,...`，`img.naturalWidth = 1`，说明该项不会破坏图片显示（此前审核的样本本身有缺陷，未覆盖到）。
- PDF 预览用 `<iframe>` 直接加载 blob，**未加 `sandbox`**：PDF 由浏览器内置阅读器在独立扩展源中渲染，读不到应用 `sessionStorage`；加 sandbox 有较大概率让 Chrome 内置阅读器拒绝渲染，属于会直接打断主功能的改动，暂不启用。若后续要加固，需要先在目标浏览器上验证 `sandbox="allow-same-origin"` 是否仍能正常翻页。

## 任务列表快捷状态按钮（2026-10-20，feature/task-quick-status）

- 需求：任务表格「操作」列在「详情」之外增加一个随状态变化的按钮——待完成→「开始」、进行中→「完成」、已完成→「重新开始」。
- 权限：仅在 `canManageTaskRow` 为真时显示（执行人/节点负责人/模块负责人/PM，排除 VIEWER 与归档项目），与后端 `requireTaskManager(id, true)` 的允许集合一致；隐藏只是提示，服务端仍校验。
- 并发保护：点击时把列表快照里的状态作为 `expectedStatus` 一起提交，后端不一致返回 409「任务状态已变化，请刷新后重试」，避免多标签页下把已变更的任务静默改错（尤其 DONE→DOING 会清空 `done_at`）。
- 交互：busy 期间其余快捷按钮 disabled，当前行显示 loading；成功后提示动作并刷新列表，`loadNode` 会按 id 同步详情弹窗里的任务快照。
- 状态机口径下沉到 `utils/task.ts` 的 `TASK_NEXT_ACTION` 并登记复用库。
- 验证：lint/typecheck/build 通过、后端 17 个单测通过；Playwright 在测试库上实测四步——待完成（开始）→ 进行中（完成）→ 已完成（重新开始）→ 进行中，按钮文案与状态严格对应，无 console 错误。

## 节点进度改为任务完成率派生（2026-10-20，feature/node-progress-derived）

- 背景：用户反馈模块/节点进度「点一下就完成」。排查确认原口径是节点 `progress` 手填 + 模块/项目对节点百分比简单平均，任务状态完全不参与，节点少时点一次「完成节点」进度就跳到 100。
- 新口径：节点进度 = 已完成任务数 ÷ 任务总数（四舍五入，未全部完成时最高 99）；无任务的节点按状态估算（未开始 0、进行中 50、已完成 100）；DONE 固定 100；取消节点不进入聚合。模块/项目仍为未取消节点进度的算术平均（`ProgressRules.aggregate`，仍有节点未完成时同样封顶 99，不按人天加权）。
- 移除手动填写：删除 `PUT /nodes/{id}/progress` 的公开接口与节点详情滑块；旧路径保留为 `@Hidden` 的废弃路由，调用返回 4090 明确提示且不写数据，OpenAPI 与接口清单均不再出现（接口总数 59 → 58）。
- 一致性约束：完成节点前校验该节点没有未完成任务，否则 4090「还有 N 个任务未完成」；已完成/已取消节点不能新增任务，避免 DONE 与进度矛盾。
- 实现：新增 `common/ProgressRules.java`（含 6 个单测）并登记复用库；节点/时间线/模块/项目查询改用 `total_tasks`、`done_tasks` 子查询后实时派生，`nodes.progress` 列保留但不再读写；Flyway V7 只把该列注释标为弃用（不删列、不丢历史值）。
- 前端：节点详情去掉 slider，改为显示「已完成 x/y 个任务」；模块卡片、节点表格、时间线、项目卡片的百分比自动变为派生值。
- 文档：接口清单删除 #19 并把 #20-#58 重排，同步开发文档、需求文档、复用库、PROJECT_MEMORY、demo_seed（去掉手填 progress 与 PROGRESS_UPDATE 演示日志）。
- 验证：后端 `mvn test` 23 个单测通过；前端 lint/typecheck/build 通过；`schema.d.ts` 从运行中的 OpenAPI 重新生成（-44 行）；本地 58080 实测节点 103/106/107 为 0/50/25、模块 1/2/3 为 50/44/33、项目 43，完成节点分别返回「还有 2/3 个任务未完成」；Playwright 在 5175 真实浏览器验证同上且页面无 slider。

### 独立审核与复审后的修复（同时）

- 第一轮审核（M1）：节点 DONE/CANCELLED 后任务仍可「重新开始」→ `taskStatus` 先对节点行 `SELECT ... FOR UPDATE`，已关闭节点只允许同状态幂等提交；`createTask` 也先锁节点行；前端节点详情/看板隐藏任务状态入口。另修任务看板接口编号、需求文档旧口径、前端重复算 x/y、before 含弃用列、临时脚本导致 lint 失败。
- 第二轮审核（B1/M1 残余）：`updateNode` 收窄 before 时漏了 `project_id`，编辑节点会 NPE/500 → 补回 `project_id`（临时库实测 `PUT /nodes/106` 返回 200，`beforeValue` 为 `{name,ownerId,planEnd,planStart,projectId,sortOrder}`，不含 progress）；完成节点的未完成任务统计在 REPEATABLE READ 下仍读旧快照 → 改为 `SELECT id ... FOR UPDATE` 当前读；前端把记录/附件也一起禁掉 → 拆成 `canManageTaskContent` 与 `canChangeTaskStatus`；模块/项目平均可进位到 100 → 新增 `ProgressRules.aggregate` 同样封顶 99；补 m3/m4/m5 文档（需求文档 :56、PLAN 单测数、99% 封顶入接口/开发文档）。
- 第三轮审核（M-A）：CANCELLED 冻结任务后，其未完成任务仍计入人员负载与周报计划人天，且任务无法删除或收尾 → `ReportingService.workload` 四个任务子查询与周报 `plannedDays` 排除 `n.status='CANCELLED'`（已发生工时仍计入实际人天）；`taskStatus` 改为节点锁后再对任务行当前读、关闭节点同状态幂等早退；`updateTask`/工时三个写路径补 `lockNode` 统一 node→tasks 锁序；文档补「取消节点任务冻结且不计入负载」口径。
- 复核验证：临时库 pt_verify_m1 实测——任务完成后节点 104 进度 0→100、节点 DONE 后重开任务返回 4090「节点已完成或已取消，任务状态不能再变更」、新增任务返回 4090、`PUT /nodes/106` 编辑成功；临时库 pt_verify_wl 实测——王工取消节点 103 前后负载 activeNodeCount 2→1、openTaskCount 2→1、activeEstimateDays 4.0→2.0、weekDueEstimateDays 2.0→0.0，任务状态变更 4090；临时库均已删除、未触碰 `proj_timeline` 库。后端 23 个单测通过（`ProgressRulesTest` 6 个）、前端 lint/typecheck/build 通过、Playwright 复核节点 106 仍为 3/6、50%、无 slider。
- 审核结论：通过（无 Blocker/Major 遗留）。

## 任务情况/处理方式与任务、模块 CRUD（2026-10-20，feature/task-fields-module-crud）

- 需求：任务补充「任务情况」「处理方式」两个字段；任务和模块补齐修改/删除（例如改名称）；模块节点视图补返回上一级（模块视图）。
- 数据库：Flyway V8 给 `tasks` 增加 `situation`/`handling`，给 `node_logs.action` 增加 `TASK_UPDATE`/`TASK_DELETE`；同步 `database.sql` 与 `demo_seed.sql`（任务 1/10 的示例）。
- 后端：创建/编辑任务读写新字段；编辑任务写 `TASK_UPDATE`；新增 `DELETE /tasks/{id}`（物理删除任务，同一事务删除进展记录与工时、软删除任务附件、写 `TASK_DELETE`）；已完成/已取消节点拒绝编辑/删除任务；模块更新/删除沿用既有 `PUT/DELETE /modules/{id}`（含节点拒绝、模块附件软删除）。
- 前端：任务表格新增编辑/删除，任务弹窗改为新增/编辑共用（名称、描述、任务情况、处理方式、执行人、截止日、计划人天），详情展示描述/任务情况/处理方式；模块工作页新增「返回模块视图」及「编辑模块/删除模块」；项目设置模块列表补删除；抽出 `components/ModuleFormDialog.vue` 供两处复用并登记复用库；Element Plus 启用 `zh-cn` locale，确认框按钮改为中文。
- 接口：新增 #59 `DELETE /tasks/{id}`，OpenAPI 59 个操作；`schema.d.ts` 从运行中的 OpenAPI 重新生成。
- 验证：后端 23 个单测通过；前端 lint/typecheck/build 通过；临时库 pt_verify_crud 实测任务创建（含情况/处理方式）→编辑→删除、模块创建→编辑→删除、含节点模块删除 409、关闭节点编辑/删除任务 409、`TASK_UPDATE`/`TASK_DELETE` 日志；Playwright 5175 实测返回模块视图、模块编辑弹窗、任务弹窗字段、任务创建/编辑/删除，无 console 错误；演示库数据已复原。
- 独立审核（子代理）结论：修复后合并，1 个 Major、若干 Minor，无 Blocker。Major：`updateModule` 未传 `sortOrder` 时用 `orElse(0)` 会把模块顺序改到最前，命中「改名称」主路径；已改为保留原 `sort_order`。同时修复：编辑任务不再把 NULL 计划人天静默改成 1、`deleteTask` 锁任务行并校验影响行数、`TASK_DELETE` 日志带 `taskId`、记录增删与删任务按 node→tasks 串行、情况/处理方式限 2000 字、权限矩阵补「删除任务」行、§4.2/§3.6 口径补全、保存按钮防重复提交。
- 备注：V8 把新 ENUM 值插在 `WORK_LOG_*` 之前，MySQL 会重建 `node_logs` 表；当前数据量小、可接受，后续再动该枚举应改为末尾追加。
- 未做：删除任务不通知执行人——通知类型契约固定 5 类，扩类型需单独决策；后续如需要再补。
- 已知未处理：`AttachmentService.upload` 对 TASK 不锁节点（先写盘再插行），与删任务并发时可能留下 `owner_name` 为空的孤儿附件（attachments 无外键，概率低）；后续统一在任务附件上传前加节点锁。
- 审核结论：通过（无遗留 Blocker/Major）。

## 节点交付物清单（2026-10-22，feature/node-deliverables）

- 起因：用户问「我们现在的项目属于 OKR 项目吗」。结论是**不改造成 OKR**——团队按项目交付为主、项目结束进维护期，管理方式是排期抓进度，没有季度量化目标、评分与对齐流程，负责人也不熟悉 OKR。判断口径、重新评估的触发条件记在 `PROJECT_MEMORY.md` 第 7 条。
- 用户真实痛点（访谈得出）：`阶段性目标` 是带日期的里程碑，回答不了「节点要交什么、还缺什么、资料是哪个节点的」。因此做**节点交付物清单**，而不是把阶段目标升级成 O/KR。
- 设计取舍：清单**平铺**，不区分「需要/产出」方向——区分只有在上下游节点之间做依赖关联时才有意义，而 P0 明确不做跨节点依赖，否则「需要」就是把上游产出再手抄一遍。归属由 `node_id` 表达，已经回答了「哪个节点的资料」。
- 数据：Flyway V9 建 `node_deliverables`（名称、状态 `WANTED/PROVIDED`、负责人、备注、可选 `attachment_id`），并给 `node_logs.action` 追加 `DELIVERABLE_ADD/UPDATE/DELETE`；同步 `database.sql`、`demo_seed.sql`（节点 103/106/107 的 5 条示例）。
- 文件复用：不新建文件体系，清单项只关联**同一节点下未删除**的节点级附件（`attachments.owner_type='NODE'`）；预览、下载、版本、软删除、按项目校验权限全部继承。附件被删除后清单保留，读取返回 `attachmentDeleted=1`，前端标「已删除」。
- 后端：`WorkService` 内实现（与任务、日志、工时同属节点子资源，复用其 `log`/`json` 私有方法与锁顺序），新增 4 个接口 `GET/POST /nodes/{id}/deliverables`、`PUT/DELETE /deliverables/{id}`；写操作走 `requireNodeManager` 并先锁节点行；节点详情与时间线额外返回 `deliverableTotal/deliverableProvided`。
- 关闭节点口径：已完成/已取消节点**只能改状态**（补标已提供是常态），改名、改派、换文件、删除一律 409；用「字段是否真的变了」判断，避免前端整表回传时误拒。
- 不强制：清单不参与节点进度、不做完成节点前置条件，只在节点详情与时间线显示「已提供 x/y」。强制会卡住不适用项，等用一段时间再决定是否收紧。
- 前端：新增 `components/DeliverablePanel.vue`（登记复用库），接入 `NodeDetailView`；`TimelineView` 节点条显示「已提供 x/y」；`types.ts` 增加 `Deliverable` 与节点计数字段。
- 验证：后端 `mvn test` 通过（首轮 23 个单测，修复后增至 29 个）；前端 lint/typecheck/build 通过；V9 在**临时库**（`proj_timeline_v9_check`，由 `proj_timeline` 结构+数据 dump 而来）验证——建表、5 条外键、`node_logs.action` 枚举扩展均成功，并对清单 SQL 做了插入/关联查询/计数/日志写入的冒烟测试；验证后临时库已删除，未触碰 `proj_timeline` 库。
- 未做：不重新生成 `schema.d.ts`（需运行中的后端；该文件当前不被应用代码引用，见 `PROJECT_MEMORY.md`），否则需另起 58080 实例。

### 独立审核与修复（同日）

- 审核（全新子代理，只看 git diff）结论：无 Blocker，1 个 Major、7 个 Minor、6 个 Nit。
- Major 修复：`deliverableValues` 原来只要算出的负责人/关联文件非空就重新校验，导致「关联文件被软删除后，连只改状态都会被 400 拦住」，而关闭节点上文件下拉是禁用的，等于该项永远无法标「已提供」。改为**只在字段真的变了时**才校验（`before` 为空或新旧 id 不同）；停用的负责人同理，不再把纯状态更新变成 400。
- 授权顺序与并发：`updateDeliverable`/`deleteDeliverable` 改为「先按 id 取 node_id → `requireNodeManager` → 锁节点行 → 锁交付物行 → 读全量」，避免先读整行造成的 404/403 探测，也避免用锁前快照判断关闭节点规则（并发下可把旧名写回）。与 `deleteTask` 的既定范式一致。
- 幂等：完全无字段变化的更新直接返回当前行、不写 `DELIVERABLE_UPDATE`，与 `taskStatus` 在关闭节点上的幂等口径统一。
- 抽公共逻辑：新增 `common/DeliverableRules`（状态归一化、`onlyStatusChanged`、`unchanged`）并补 6 个单测、登记复用库；前端新增 `utils/member.ts` 的 `selectableMembers`，替换 `ModuleFormDialog`/`ModuleDetailView`/`NodeDetailView`/`DeliverablePanel` 四处重复 filter 并登记复用库。修掉 `DeliverableRules.status(null, null)` 因 `Set.of(...).contains(null)` 触发 NPE 的边界（改为非法值 4000）。
- 收敛：`createTask`/`updateTask`/`deleteTask` 三处内联的关闭节点判断回填 `requireNodeOpen`；`taskStatus` 因有幂等分支保留内联并注释原因。`node()` 内嵌清单改用不重复鉴权的 `listDeliverables`，省两次查询。
- 文档：接口文档 §3.8 补 `sortOrder` 与日志字段措辞、§5 页面映射补 #60-#63；需求文档补排序与「关闭节点不能换文件/改备注/改排序」；`docs/部署与运维.md` Flyway 版本改 V9 并补 V9 回退步骤；`demo_seed.sql` 固定 `created_at`；前端时间线文案改为「已提供 x/y」。
- 端到端验证（真实数据库 + 真实接口）：把 `proj_timeline` dump 到临时库 `proj_timeline_e2e`，用 `SPRING_DATASOURCE_URL` 指向它、58081 启动后端，Flyway 自动把 V8→V9；15 项断言全部通过——新增、非法状态 400、时间线计数、标已提供、无变更幂等且不写日志、跨节点附件 400、已完成节点新增/删除 409、已完成节点只改状态 200、改名 409、VIEWER 写 403 / 读 200、删除后计数回落。另单独回归 Major：关联文件软删除后仅更新状态返回 200 且列表返回 `attachmentDeleted=1`（修复前为 400）。验证后临时库、临时文件与 58081 实例均已清理，未触碰 `proj_timeline` 库。
- 复验：`mvn test` 29 个单测通过（含新增 `DeliverableRulesTest` 6 个）；前端 lint/typecheck/build 通过。
- 复审结论：通过（复审子代理原话：「通过（无遗留 Blocker/Major）」）；复审另指出 1 条显示回归——关闭节点编辑时因跳过 `loadFiles` 导致关联文件下拉显示裸 id，已改为只跳过新建场景，并复跑 lint/typecheck/build。

## 阶段性目标量化指标（2026-10-22，feature/stage-target-metric）

- 起因：P0 交付物上线后，访谈里勾选的痛点「季度目标达成了多少（要量化数字）」仍没有落点；但已确认不做 OKR。折中方案是给阶段性目标加**可选**量化指标，不引入 O/KR、周期、评分与对齐。
- 数据：Flyway V10 给 `project_stage_targets` 加四个可空列（`metric_name/metric_unit/metric_target/metric_current`）。四列全空 = 原来的日期型里程碑，老数据不需要任何处理，向后兼容。
- 口径：有指标时 `metric_name` 与 `metric_target` 必填、`metric_target > 0`、`metric_current` 缺省 0 且不得为负；`progress = 当前值 ÷ 目标值`，四舍五入后夹 0–100，超额按 100 显示。进度读取时派生、不落库，规则集中在 `ProgressRules.metricProgress`（已登记复用库，补 3 组单测）。
- 兼容：编辑目标时若请求体完全不出现四个指标字段，保留原指标（旧前端缓存只传名称/日期时不会静默清空）；显式把四个字段都传 `null` 才清空。
- 接口：没有新增接口，仍是 `#47`/`#48`；请求体扩展 4 个可选字段，响应在有指标时多一个 `progress`（无指标时因全局 `non_null` 策略省略该键），接口总数仍为 63。
- 前端：项目设置的阶段目标表新增「量化指标」「达成度」两列，对话框加可选指标区（勾选后才填）；时间线阶段目标标签显示「达成 x%」；不填指标时行为与之前完全一致。
- 边界：指标只做展示与复盘参考，不参与项目/模块/节点进度聚合、不进周报；也不做“目标挂节点自动派生”（留待确有需要再加）。
- 回滚：停服回退到 V9 应用版本后 `ALTER TABLE project_stage_targets DROP COLUMN metric_name, DROP COLUMN metric_unit, DROP COLUMN metric_target, DROP COLUMN metric_current`；会丢失已填的指标值，其余目标字段不受影响，无需清理磁盘文件。
- 验证：后端 `mvn test` 33 个单测通过（`ProgressRulesTest` 新增 3 组指标用例）；前端 lint/typecheck/build 通过；临时库端到端（`proj_timeline` dump 到 `proj_timeline_metric_check`，58082 起后端，Flyway 自动 V9→V10，`flyway_schema_history` 与 `project_stage_targets` 列均已确认）12 项断言全过——建带指标目标 `1/3→33%`、达成 `3/3→100%`、超额 `5/3→100%`、缺目标值/目标值 0/负当前值均 400、纯日期目标 `progress=null`、四字段传 null 可清回 `null`、VIEWER 写入 403、时间线带出 `progress`、删除清理。验证后临时库、临时文件与 58082 实例均已清理。
- 独立审核（全新子代理）：通过，无 Blocker/Major；提 5 条 Minor、10 条 Nit。已修：`metricProgress` 改为先比较再相除（超大比值曾因 `intValue()` 溢出算出 0%）；补 `DECIMAL(12,2)` 的数值上限与小数位校验（越界会变成 409、`0.001` 会被数据库取整成 0）；接口文档 #7 与 §3.7 补指标字段与「无指标时省略 progress」；`PROJECT_MEMORY` 单测数；边界用例。另把编辑语义收紧为「请求体完全不出现指标字段＝保留原值」，旧前端缓存只传名称/日期时不会静默清空指标——用临时库手工验证：只传 name/targetDate 的 PUT 保留 `metricTarget=3.00`、`progress=33`，显式四个 null 的 PUT 清空成功；临时库与 58083 实例已清理。

## 模块变更申请（2026-10-22，feature/module-change-request）

- 起因：用户最早描述的权限诉求——「项目成员只能进自己参与的项目，管理自己负责的模块，改别人的模块需要申请」。这是权限缺口，不是新概念，所以按最小闭环做。
- 范围（已确认）：只覆盖模块的**名称 / 描述 / 负责人**；只 PM 批；通过后**自动生效**；新增「待审批 / 审批结果」两类站内通知。
- 数据：Flyway V11 建 `module_change_requests`，用 `pending_module_id` 生成列 + 唯一键保证同一模块同时只有一个待审批申请；`notifications.type` 扩到 7 类（原 5 类末尾追加）。
- 后端：`ChangeRequestService` 负责提交、列表可见性、审批、撤回。提交要求项目成员且非 VIEWER，已有编辑权限（PM/管理员/模块负责人）返回 409；审批与改模块同一事务，审批前重新校验负责人仍可指派；驳回必须填原因；撤回仅申请人本人且仅待审批。
- 通知：抽 `NotificationService` 作为唯一写入口，`WorkService.notifyUser` 与 `ReportingService` 周报发布改为调用它，避免出现第三份 `INSERT INTO notifications`。
- 前端：新增 `ChangeRequestDialog`（模块页，无编辑权限的成员可见）；项目设置新增「模块变更申请」表——PM 可通过/驳回，申请人可撤回，状态与审批意见可见；`types.ts` 增加 `ChangeRequest`。
- 验证：后端 33 个单测、前端 lint/typecheck/build 通过；空库起后端 Flyway 一次跑完 V1–V11，导入 `demo_seed` 后在临时库跑 10 项断言全过——成员提交、同模块重复 409、模块负责人自提 409、列表可见性（PM 全部 / 成员仅自己）、通过即改模块、申请人收到 `CHANGE_REQUEST_DECIDED`、驳回不填原因 400、仅申请人可撤回（PM 撤回 403）、内容无变化 400、VIEWER 提交 403 查询 200。临时库与 58084 实例已清理，未触碰 `proj_timeline`。
- 未做：不支持批量审批；已通过的申请不能“撤回”（模块已改，只能再提一个新申请改回）；申请人提交时不通知被申请模块的负责人。
- 回滚：停服回退到 V10 应用版本后，先 DELETE FROM notifications WHERE type IN ('CHANGE_REQUEST_SUBMITTED','CHANGE_REQUEST_DECIDED')，再把 
otifications.type 的 ENUM 改回 V1 的 5 个值，最后 DROP TABLE module_change_requests（先删通知，否则缩枚举会失败或截断）。
### 独立审核与修复（同日）

- 审核（全新子代理，只读）结论：需修复后通过；1 个 Major + 11 Minor + 15 Nit。已实跑后端 33 单测与前端 lint/typecheck/build。
- Major（已修）：V11 的 `fk_request_module` 是 RESTRICT，而模块是物理删除——模块一旦有申请记录（含已驳回/撤回）就永远删不掉，返回 409「数据约束冲突」。`deleteModule` 改为：锁模块行 → 有待审批申请先返回明确 409 → 同事务删除该模块的申请记录 → 再软删模块文档、删模块；并给 `module_id` 补显式索引。
- 加锁顺序：审批改为「模块行 → 申请行」，与 `deleteModule` 同向，避免与删模块互等。
- 并发提示：唯一键兜底现在 catch `DuplicateKeyException` 转成「该模块已有待审批的变更申请」，不再暴露「数据约束冲突」。
- 通知复用：`NotificationService` 增加 `notifyProjectPm`，`WorkService.notifyPm` 与 `ChangeRequestService.create` 都改用它，查 PM 的 SQL 只剩一份。
- 审批参数：`approved` 只接受 JSON 布尔，`{"approved":1}`/`"yes"` 不再被 `Boolean.valueOf` 静默当成驳回。
- 前端：归档项目隐藏通过/驳回/撤回；提交成功后模块页显示「变更申请待审批」；设置页文案改为指向模块页入口；状态映射改成 `Record<ChangeRequestStatus, …>`；通知中心用 `notificationLabel` 显示中文类型，不再露出英文枚举。
- 文档：接口文档修「同日待审批」笔误、补归档语义与页面映射去重；需求文档补 7 类通知、ER 图与实体清单、权限矩阵「撤回」行；`PROJECT_MEMORY` 数字改 67 接口 / 16 表；演示数据补一条 `CHANGE_REQUEST_SUBMITTED` 通知；本段补回滚步骤。`schema.d.ts` 用运行中的 OpenAPI 重新生成（含 4 个 change-request 路径）。
- 复验：后端 33 单测、前端 lint/typecheck/build 全过；临时库与 58085 实例已清理。

## 工作项模型与缺陷路由（2026-10-20，feature/work-item-model）

- 背景：用户提出「模块按功能分，节点到底该叫什么、测试/维护/bug 修复放哪里」。讨论后确认：模块只做功能划分；节点概念升级为**工作项**；跨模块的测试、维护、缺陷修复、上线保障放到**项目级工作项**（`nodes.module_id` 为空），不再建“测试模块/维护模块”。
- 模型：项目 → 模块 → 工作项 → 任务，另加项目级工作项。工作项类型 `FEATURE/TEST/MAINTENANCE/BUGFIX/OTHER`；任务类型 `FEATURE/FIX/BUG/TEST/OTHER`，并可用 `related_node_id` 关联原工作项。UI 统一把“节点”叫“工作项”，数据库/API 继续用 `node`。
- 数据：Flyway V12 让 `nodes.module_id` 可空、增加 `nodes.type`；给 `tasks` 增加 `kind`、`related_node_id`（含索引与外键）。已在临时库按 V1→V12 顺序验证复合外键下 `module_id` 可空，并验证项目级工作项 + 关联任务的插入；主库重启后自动应用 V12。
- 后端：`PermissionService` 改用 `LEFT JOIN modules`，项目级工作项由 PM/ADMIN 或工作项负责人管理；`createNode` 的 `moduleId` 可空，空时要求 `requireProjectManager`；`updateNode` 支持改类型；时间线拆出 `projectWorkItems`（模块节点不再包含项目级工作项）；任务列表/详情返回 `kind`、`relatedNodeId`、`relatedNodeName`；新增 `POST /projects/{id}/bugs`（#68），按“原工作项进行中→原工作项；否则→当前进行中的项目级 BUGFIX 工作项”路由，任务类型固定 `BUG`，执行人=落点工作项负责人，并复用 `NotificationService` 发 `TASK_ASSIGN`。
- 前端：新增 `WorkItemFormDialog`（模块级/项目级新建与编辑共用）和 `BugReportDialog`；时间线增加“项目级工作项”泳道，模块视图增加项目级工作项区块；任务弹窗/表格/详情增加类型与关联工作项，任务区支持按类型筛选；节点/模块/看板/周报/负载/文档等文案统一改为“工作项”；新增 `utils/workItem.ts` 与任务类型常量并登记复用库。
- 接口：新增 #68 `POST /projects/{id}/bugs`，OpenAPI 68 个操作；`schema.d.ts` 从运行中的 OpenAPI 重新生成。
- 权限：模块工作项仍由模块负责人/PM 管理；项目级工作项由 PM/ADMIN 创建，负责人可为任意有效成员；成员提缺陷只允许项目成员（非 VIEWER），提交人不能指定执行人/状态/计划人天，任务建好后只有工作项负责人/模块负责人/PM/ADMIN 能编辑、改派、删除。
- 验证：后端 33 个单测通过；前端 lint/typecheck/build 通过；临时库 pt_verify_wi（已删除）18 项断言通过——进行中工作项提缺陷落到原工作项（kind BUG、负责人=工作项负责人、关联原工作项）、DONE 工作项且无 BUGFIX 工作项返回 409、创建项目级 BUGFIX（module 为空）、开启后 DONE 工作项的缺陷与无关联缺陷都落到该 BUGFIX、VIEWER 403、成员编辑/删除缺陷任务 403、模块 TEST 工作项可创建、时间线 `projectWorkItems` 只含项目级且模块节点不含项目级、任务 kind/related 创建与更新、`relatedNodeName` 回传。主库 V12 应用后 Playwright 5175 实测：项目级泳道/类型标签/面包屑、模块视图项目级区块、任务弹窗类型/关联字段、成员提缺陷成功提示“已提交到「订单接口开发」”、缺陷任务可见，无 console 错误；测试数据已清理，演示库恢复 6 条任务、无项目级工作项残留。
- 已知未做：项目级工作项暂无删除接口（工作项本身一直不支持删除）；提缺陷暂不支持提交人直接上传附件（任务附件权限仍归落点工作项负责人/管理者）；不做重复缺陷合并、严重程度、提交人通知；缺陷路由依赖数据库状态，暂无 Spring 集成测试（用临时库端到端覆盖）；`openBugfixWorkItem` 的 `FOR UPDATE` 没有覆盖索引（补索引走 V13）；NodeDetail 每次多拉一次 timeline 供关联工作项下拉。
- 独立审核（子代理）结论：修复后合并，无 Blocker；1 个 Major、若干 Minor/Nit。Major：
  1. ADMIN 非项目成员新建项目级工作项时默认负责人填成自己 → 前端改为从成员列表取项目 PM 作默认负责人；
  2. `WorkItemFormDialog` 的编辑分支不可达 → NodeDetail 增加「编辑工作项」入口，接入 `:work-item` 与 `PUT /nodes/{id}`。
- 同时修复：需求/开发文档“不做 Bug 管理”与 F4.1 冲突、需求权限矩阵项目级工作项负责人权限、名称统一 ≤200 校验、`ownerId/assigneeId` 的 0 归一为 null、删除未使用的 `requiredLong`、demo_seed 误替换文案、接口设计 4.3 负责人说明、时间线/模块视图 `active` 严格判断、编辑按钮等 `node` 加载后再显示。
- 复审结论：可以合并（无 Blocker/Major）；V12 保持未改写，主库 Flyway 校验通过。
- 回滚：停服回退到 V11 应用版本后，先删除 `related_node_id` 外键与索引、`tasks.kind`、`nodes.type`，再把 `nodes.module_id` 改回 `NOT NULL`；项目级工作项数据需先迁移到某个模块或删除，否则回退会失败。

## 用户体验走查（2026-10-26）

- 用户反馈整体繁琐、目的不清晰、操作流程不明确。本轮按用户工作目的诊断，未进行功能重构。
- 已先读项目记忆、复用库、需求与相关界面/权限源码；使用真实本地前后端，以演示 PM、执行成员、VIEWER 体验主要路径。在默认约 929px 和 1440px 宽度检查时间线、任务页面与表单。
- 浏览器覆盖项目总览、时间线、设置、模块列表/详情、工作项详情、任务详情/看板、周报、人员负载、通知与提缺陷表单。项目文档、创建项目后的跳转、风险与缺陷分流通过源码核对；未提交业务写操作。
- 明确发现：`App.vue` 用 `route.params.id` 作项目 ID，工作项页生成错误项目链接，模块详情则丢失项目侧栏；时间线固定同一高度导致条块遮挡且无日期轴；普通成员缺少“我的任务”，却能看到部分管理按钮；延期说明没有前端填写入口，受阻提交固定说明；任务表单一次展开 9 个字段，看板无法打开任务详情。
- 新增 [用户体验走查与简化建议](docs/用户体验走查与简化建议.md)，记录可复现问题、证据边界、角色路径和三批实施建议；项目记忆登记此次反馈，未把建议写成已确认产品决定。
- 环境：原先没有 5173/58080 服务监听，为体验启动现有 Vite 与后端 jar；健康 `UP`、前端 HTTP 200。本次关闭 Flyway 自动迁移，未导入种子、未改数据库。演示排期已过期，不把延期数量作为缺陷依据。
- 验证范围为浏览器阅读/导航/表单展开与源码核对；仅文档变更，未运行 Maven/lint/typecheck/build，未提交或推送。后续实现仍需按功能分支、独立审核与角色路径验证流程执行。

## 体验改善设计与静态原型（2026-10-27）

- 用户明确要求“先设计好改善文档和改善的原型图，先不写代码”。本轮范围为设计交付，没有修改前后端、接口生成类型或数据库迁移，也没有进入功能实现/发布流程。
- 新增 `docs/体验改善设计方案-v1.md`：产品目的、全局与项目导航、8 条使用流程、关键页面字段与交互、角色/生命周期矩阵、日期统计、错误/空状态、响应式与后续验收；新增 `prototype/ux-v1/` 图册与 PNG 静态原型，生成提示词一并保存。
- 原型统一用 2026-10-27 示例业务日期；商城示例 6 个工作项按既有口径得到 33%，订单工作项 3/6 个任务为 50%，个人任务列表跨项目 4 项。示例不导入现有数据库。
- 设计延续现有层级、项目级工作项、阶段目标归属、模块无阶段日期、自动进度、交付物非门槛、历史周报快照与 sessionStorage 约定。任务表单折叠补充项，个人执行与项目管理入口按权限分别呈现。
- 补充记录模块自身编辑的契约冲突：项目记忆/申请服务认为模块负责人可直接编辑，`ProjectService.updateModule`/接口表/当前 UI 则仅 PM；方案建议按已确认决定统一，单独作为实现前的契约对齐事项，未暗中修改权限。
- 视觉验证逐张检查中文、任务数/进度、日期与阶段目标位置；发现生成图字数限制、背景示例数据和日期刻度偏差后采用内置 image_gen 定向修订。P06 最终按统一日期数据从空白画布精确绘制，验证 28 日线性映射与 6 条计划条，避免直接采用生成图中不准确的条块比例。这里只验证设计资产及文档一致性，不将图片当成可运行页面或业务测试证据。
- 交付检查：22 个本地文档/图片引用无缺失，最终 8 张 PNG 均可解码，`git diff --check` 通过，前后端目录无本轮改动。图册包含最终选用资产、生成/修订提示词和设计校对记录；移动端目前只有适配规格，静态图不含可点击交互。


## 2026-10-27 · 按已批准体验方案实施

- 用户在完成文档/静态原型评审后明确要求“按照这个方案进行改动”。从最新 develop 建立 feature/experience-improvements；依照 docs/体验改善实施计划.md 冻结共享接口并分配隔离工作树，逐项集成。
- 完成我的工作/项目概览、工作项统一创建、任务抽屉和简表、固定项目导航、独立日期行时间线、周报与设置分签、缺陷预判/回执、通知与负载明细。保留 sessionStorage、业务 Clock、原实体和进度规则。
- 新增 5 个读取接口（OpenAPI 共 73 个）；模块负责人可直接编辑名称/描述/负责人；新工作台在 SQL 内过滤分页，USER 不泄露非成员项目/人员；新旧路由兼容。
- 验证：Maven package 55 项测试通过；前端 lint/typecheck/build 通过；Playwright 15 项角色/任务/草稿/分页/周报回归通过。从运行中的最终后端生成 schema.d.ts。
- 使用明确命名的本机隔离 MySQL 库运行真实 HTTP，包括权限/范围/状态冲突/完成门槛/缺陷/周报/归档/负载与附件归属。无头 Chromium 真实页面检查与 4 张截图完成，未发现运行异常。截图与详细证据见 docs/体验改善验收/README.md。
- 独立审核使用全新上下文，发现并复核修复 5 项 P2：本周计划展开漏 DONE、权限变化隐藏草稿、关闭项受阻计入当前风险、周报请求中切周串写、末页收缩后假空列表。当前无未修复 P1/P2。
- 无新迁移、无 DDL/种子修改、未改 V1—V12。测试数据只写隔离库。文档/复用登记及当前记忆已同步。
- 已知边界：现有 Element Plus 主包 >500kB 警告保留；只验证 Chromium，Firefox/Safari、真实团队长期可用性研究未覆盖。早期设计中的“暂不写代码”记录属于前一阶段，不覆盖本次明确实施授权。

- 交付运行检查：临时测试库和 58082/5175 测试服务已清理；新版后端 58080 健康 UP，前端 5173 HTTP 200，使用原本机数据库。

- 独立最终审核通过后，功能分支以合并提交 `31d38bc` 集成并推送 develop；main 未变。已删除远端/本地 feature/experience-improvements 及本轮三个工作分支，移除隔离工作树，工作区干净。


## 2026-10-27 · 修复新建模块不显示

- 用户反馈新建模块未显示。定位 WorkItemsView 的 groups 无条件按 nodes.length 隐藏空模块；刷新已成功，数据无需重建。
- 从最新 develop 的 9eeaa9d 建立 feature/show-empty-modules。默认列表及仅所属位置筛选保留真实空模块分组；按工作项内容/负责人/状态/风险/管理范围筛选仍只保留匹配分组。空模块可进入工作页，并按原职务/归档限制新建工作项；只有取消历史时使用准确空态，不显示虚假“项目还没有工作项”。
- 先用浏览器复现创建成功、关闭管理弹窗后主列表没有模块，再修复。新增4项回归覆盖创建→立即显示→新建工作项预填→刷新→模块工作页、筛选、VIEWER与归档；全套19项通过。
- 前端 lint/typecheck/build 通过；git diff --check 通过。后端、API、数据库及迁移均未变，不重跑后端测试。复用库、当前记忆、需求与设计规格已同步。
- 用户现有 Vite 5173 返回200，页面源码已热更新包含修复；无需重启后端。独立审核通过（无 P1/P2），已以 `33b70ba` 合并推送 develop，功能分支已清理。


## 2026-10-27 · 工作项作为最小功能交付单位

- 用户澄清工作项是模块下小功能，只需交图/文档并跟进后续Bug，随后明确授权更新。新契约覆盖旧默认子任务和任务完成率决定；见docs/功能交付模型实施计划.md。
- 从最新develop建立feature/feature-delivery，冻结类型/权限/接口后，三份隔离工作树分别实现后端、详情和工作台，逐项集成。功能页默认说明/资料/Bug/活动记录，旧任务与附件保留历史；我的工作与人员负载改为功能/Bug责任；其余项目视图显示已交付x/y。
- Bug复用原tasks.kind=BUG及记录/附件；已交付功能可继续报告、修复，来源跨项目不追溯，取消/归档/VIEWER受限。功能由负责人确认，资料/Bug/旧任务不推算进度或自动阻断。原API和已发布周报快照保留兼容，新增两个工作台读取，共75个API。
- 集成验证：Maven package71项全通过；前端lint/typecheck/build通过；Playwright29项全通过。最终运行后端生成schema。独立MySQL/真实HTTP验证交付与历史、Bug来源/权限/状态/快照；Chromium实际上传资料、交付后报告Bug并保存修复记录，4张截图已检查。
- 独立审核2项P2均修复：旧createTask Bug统一报告来源，普通历史任务不可转Bug；关闭项当前风险展示统一hasWorkItemRisk，底层受阻历史保留。后端及浏览器回归已增加。
- 无迁移/DDL/种子变更，测试数据仅写明确隔离库。用户原库新版58080健康UP，5173热更新。详细证据与边界见docs/功能交付验收/README.md；当前仍有既有主包500kB警告，Firefox/Safari、Docker与长期用户研究未覆盖。

- 最终提交独立复核通过（6800060功能代码，无剩余P1/P2）；修正记忆中一处旧接口数字。隔离库与58082/5175服务已删除，三个实现工作树及依赖链接安全回收，主目录依赖与用户库保留。

- 最终独立审核通过的 `3f4ce1e` 已通过合并提交 `e24af82` 集成develop；与验收代码一致，main未发布。测试库、测试服务和实现工作树已回收，用户新版服务保留运行。

## 2026-10-27 · 局域网访问（本地试用服务）

- 现象：其他机器用 `http://<本机IP>:5173` 访问被拒绝。排查发现试用中的 Vite 是以 `--host 127.0.0.1 --port 5173 --strictPort` 启动的，只监听回环地址；后端 58080 已监听全网卡、`/actuator/health` 正常，所以只需改前端监听地址。
- 改动：`frontend/vite.config.ts` 增加 `server.host: true` 与 `strictPort`（保留原有 `/api`、`/actuator` 代理到 58080），并把旧 `--host 127.0.0.1` 进程换成不带该参数的新进程；文档同步 `README.md`、`docs/部署与运维.md`（新增「局域网访问」小节，含防火墙排查与 `New-NetFirewallRule` 命令）、`PROJECT_MEMORY.md`。
- 验证：`npm --prefix frontend run lint` 与 `build` 通过（仅既有 500kB 分包警告）；重启后 5173 监听 `::`（等价 0.0.0.0），`http://192.168.0.18:5173/` 返回 200、`/actuator` 代理 200、`/api` 代理 401（未登录，符合预期）。本机网络配置文件为「专用」，防火墙已有 node.exe（v24.9.0）入站放行；未从真实第二台机器联调，若仍被拒按文档核对防火墙。
- 独立审核（新会话）确认无 P1，并独立复现：5173 监听 `::`，LAN 页面/模块/HMR（`ws://192.168.0.18:5173/?token=…`）/`/api`/`/actuator` 全通；Vite 7 `allowedHosts` 默认放行 IP 字面量、拦主机名（403）；`playwright.config.ts` 用 `--host 127.0.0.1 --port 41731` 覆盖，e2e 仍隔离在回环。按审核建议补齐文档：代理默认端口 58080 与后端默认 8080 的口径差异（需 `API_PROXY_TARGET`）、多网卡选 IP、主机名需登记 `allowedHosts`、`strictPort` 报错处理、防火墙按 `Get-NetConnectionProfile` 确认类别，并明确 dev server 仅限可信内网。
- 服务以独立进程方式启动（`Start-Process` 脱离会话，日志 `.tmp/vite-dev.log`，`.tmp` 已在 `.gitignore`），避免会话结束带走开发服务。

## 2026-10-28 · 恢复独立模块入口

- 用户发现“模块消失”。只读检查原数据库仍有 12 条模块记录；实际原因是此前 `/projects/:id/modules` 直接复用工作项页，顶部只显示“工作项”，模块管理藏在弹窗，项目概览也不列模块。
- 从最新 develop 建立 `feature/restore-modules-navigation`。恢复顶部“模块”标签和独立模块卡片页；项目概览直接展示模块、负责人及交付数；模块详情的面包屑和返回动作指向模块列表；工作项页保留按模块分组与空模块可见，并提供返回模块列表的入口。
- 新建模块复用原 `ModuleFormDialog`，默认负责人为 PM；未新增 API、数据表或迁移，现有项目/模块/工作项关系保持不变。
- 验证：前端 lint/typecheck/build 通过；完整 Playwright 31 项通过（含新建空模块、刷新、概览入口、模块导航、VIEWER/归档、工作项筛选与 320px 页面宽度）；`git diff --check` 通过。5173 前端页面与新版模块源码均返回 200，58080 健康接口返回 200。后端未改动，不重跑 Maven。
- 独立审核发现 320px 视口模块卡片最小宽度会导致横向溢出，已改为随可用宽度收缩并针对窄屏加回归。审核还指出需求文档遗留的旧任务完成率公式，与现行契约矛盾，已同步修正。
- 最终独立复核 `d410618` 相对 develop 无剩余可操作问题，可合并；审核环境缺少 Chromium 可执行文件，浏览器 31 项结果由主工作区执行，审核者核对了结果和源码。
- 已审核功能以 `859577a` 保留合并提交集成 develop；合并后的 frontend 与受审功能分支内容一致，main 不变。

## 2026-10-28 · 进展记录改为命名的 Markdown 文件

- 用户要求：修复区的编辑区兼容 Markdown；附件可直接粘贴到对应位置，图片内联预览、文件以超链接占位点击查看；粘贴的文件同时出现在附件区；编辑内容保存为可命名的 `.md` 文件并在“进展记录”展示，一个任务可有多份。
- 从最新 develop 建立 `feature/markdown-progress-records`。数据层扩展现有 `task_notes`（Flyway V13）：新增 `title`（默认「进展记录.md」，记录名即文件名），`content` 扩为 `MEDIUMTEXT` 保存 Markdown；不新建表、不改附件表，粘贴文件仍是普通 `TASK` 附件，新增分类 `TASK_RECORD`「进展记录附件」。
- 后端 `taskNotes/addTaskNote` 返回并保存 `title`：≤120 字，自动补 `.md`，拒绝 `\ / : * ? " < > |` 与控制字符；正文 ≤20000 字、不能为空，默认名「进展记录.md」。权限、归档、删除与草稿保护沿用原记录规则。
- 前端新增 `utils/markdown.ts`（`markdown-it`，`html:false`，`attachment:{id}` 占位渲染）、`MarkdownView.vue`（Blob 内联图片、`.md-file` 点击预览/下载）、`MarkdownEditor.vue`（工具条、编辑/预览、粘贴/拖入即上传并插入占位），`TaskDrawer.vue` 改为“记录名称 + Markdown 编辑区 + 保存记录”，记录列表按文件名展示并可下载 `.md`；粘贴后刷新附件页签，全局 `.markdown-body` 承载排版。
- 验证：Maven 72 项通过；前端 lint/typecheck/build 通过；Playwright 32 项通过，新增「保存为命名 .md + 粘贴截图进正文与附件区」；在隔离库 `proj_timeline_mdnote_verify` 执行 V1–V13 迁移并真实 HTTP 验证上传、保存/读取、默认文件名与长度/字符校验，之后删除测试库、停止临时服务。用户正在运行的 58080 后端（IntelliJ 启动）已停止，改用 `mvn spring-boot:run` 后台运行；V13 已应用到本机 `proj_timeline`（迁移前 0 条记录），健康 UP、OpenAPI 75 个操作，5173 页面 200、`/api` 代理 401。
- 兼容与回滚：V13 只加列/扩列，旧记录标题取默认值、正文原样保留；回滚需删除 `title` 列并把 `content` 收回 `VARCHAR(2000)`，仅在无超长 Markdown 记录时可执行（旧纯文本记录均 ≤2000 字）。MySQL 8 把 `VARCHAR(2000)` 扩为 `MEDIUMTEXT` 会 COPY 重建表并短暂写锁；本机表 0 行瞬时完成，生产大表执行前需备份并选择低峰或在线变更工具。
- 待办：无。独立审核（首轮 + 增量复审，均只读、新上下文）结论“可合并”，无 P1/P2；功能提交 `12b127c` 已以合并提交 `d02d578` 集成 develop 并推送 `origin/develop`，功能分支已删除，工作树干净。审核遗留的 P3 已记录：粘贴即上传的孤儿附件、预览态粘贴/非文件拖放、外部图片追踪像素、记录全文无分页、历史快照文档措辞。用户库在重启时已由 Flyway 自动应用 V13（迁移前 0 条记录）。

## 2026-10-28 · 进展记录编辑体验补强

- 用户回复“继续”。从最新 develop 建立 `feature/markdown-record-polish`，处理上轮审核的 P3 中与编辑区直接相关的项：
  - 编辑区新增「本次上传」附件列表，保存前可逐个移除；移除调用 `DELETE /attachments/{id}` 软删除该任务附件，并从正文去掉全部 `attachment:{id}` 占位，父组件同步任务附件列表与「附件」页签。
  - 粘贴事件提升到编辑区容器：焦点在编辑区内时，预览态粘贴自动切回编辑再上传并插入占位；拖放仅在剪贴板含文件时 `preventDefault`，非文件拖放不再被拦截。
  - 外链图片增加 `referrerpolicy="no-referrer"`，减少向第三方泄露来源页面地址；未开放原始 HTML、未新增服务端接口。
- 新增两条 e2e：粘贴附件保存前移除（断言 `DELETE /attachments/{id}` 与占位消失，覆盖文件名含 `[ ]` 的转义与本轮非图片链接）、预览态粘贴切回编辑并插入占位。
- 复审发现移除正则对含 `[`/`]` 的文件名失配（附件已删但占位残留），已改为转义感知正则并去掉全局换行折叠；同时补充保存中粘贴提示、移除按钮跟随 `canUpload` 灰化，并把预览态粘贴的文档口径限定为“焦点在编辑区内时”。
- 验证：Maven 72 项通过；前端 lint/typecheck/build 通过；Playwright 34 项通过（31.6s）；`git diff --check` 通过。本轮无后端、接口、迁移或数据库变更，58080/5173 服务保持运行。
- 待最终复审后合并 develop。
