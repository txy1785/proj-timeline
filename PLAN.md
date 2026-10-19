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
