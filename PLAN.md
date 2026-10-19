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


