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

## 当前实现定位

| 内容 | 当前位置 |
|---|---|
| 项目、成员、模块、阶段目标与时间线业务 | `backend/src/main/java/com/projtimeline/project/` |
| 节点、任务、逐日工时 | `backend/src/main/java/com/projtimeline/work/` |
| 项目权限 | `backend/src/main/java/com/projtimeline/security/PermissionService.java` |
| 项目设置与阶段目标编辑 | `frontend/src/views/ProjectSettingsView.vue` |
| 模块卡片与模块工作页 | `frontend/src/views/ModulesView.vue`、`ModuleDetailView.vue` |
| 项目时间线、节点详情 | `frontend/src/views/TimelineView.vue`、`NodeDetailView.vue` |
| 路由、会话、HTTP 客户端 | `frontend/src/router.ts`、`stores/auth.ts`、`api/client.ts` |

- 后端：Java 17、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、Spring Security、Flyway、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Vue Router、Pinia、Element Plus、Axios；使用 npm 和 `package-lock.json`。
- 当前 Flyway 版本为 V3。V1 建表，V2 将项目开始日期设为必填并过渡性增加模块日期，V3 建立 `project_stage_targets` 并移除模块日期。已有迁移文件不能改写；新变更追加 V4 及以后版本。
- 当前 OpenAPI 有 50 个 `/api` 操作。前端生成类型在 `frontend/src/api/schema.d.ts`，后端运行时用 `npm --prefix frontend run api:types` 更新。
- 认证为 8 小时 JWT，仅保存用户 ID；每次请求重新检查账号启用状态与角色。浏览器会话存于 `sessionStorage`。
- 日期判定使用注入的 `Clock`，默认业务时区 `Asia/Shanghai`。延期和临近到期由服务端实时计算。
- 演示数据只在显式执行 `docs/demo_seed.sql` 时导入；不属于 Flyway 正式迁移。根目录 `.env` 和本机数据库凭据不得写入文档或提交。

## 权限和数据边界

- 全局角色：`ADMIN`、`USER`、`VIEWER`。ADMIN 可管理全部项目；VIEWER 可全局读取业务数据，但不能写入业务数据；USER 只读取其加入的项目。
- 每个项目恰有一个有效 PM。PM 管项目、成员、模块、阶段目标和周报。模块负责人管理自己模块下的节点和任务；节点负责人管理自己的节点和任务；任务执行人管理自己的任务状态和工时。实际授权必须由后端校验，前端按钮可见性只是提示。
- 归档项目保留可读历史；除恢复项目外，项目业务写操作被拒绝。改派目标必须是本项目有效且非 VIEWER 的成员。
- 项目进度按未取消节点的进度直接平均；节点完成强制进度 100%。延期、临近、受阻分别保留标记。周报按周一至周日归周。

## 验证与已知边界

- 基本检查：`mvn -gs .mvn/settings.xml -s .mvn/settings.xml -f backend/pom.xml test`；`npm --prefix frontend run lint`、`typecheck`、`build`。浏览器路径测试在 `frontend/tests/roles.spec.ts`。
- `PLAN.md` 中早期“47 个接口”“11 个页面”“10/11 张表”是当时版本的验证快照。当前接口为 50 个，页面已新增模块工作页；不能把早期数字当成现状。
- 当前工作站此前未运行 Docker Compose 的真实构建；部署仍需在具备 Docker 的目标环境验证。
- Git 远程：`https://github.com/txy1785/proj-timeline.git`。分支模型（2026-10-19 建立）：`master` 版本上线、`develop` 开发主线、`feature/<描述>` 功能分支从 `develop` 拉出；功能代码必须经独立审核（干净会话/子代理）后合并 `develop`，发布从 `develop` 合并 `master` 并打 tag。GitHub 默认分支已切换为 `master`，远程仅保留 `master` 与 `develop`（`main` 已删除）。提交前先 `git status` 核对未被 .gitignore 遗漏的文件。
- 曾在本机启动 Vite 5173 与后端 8080 供用户试用；进程是否仍运行应实时检查，不能依据本文假定。最近浏览器停在登录页，可能只是会话过期。
