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











