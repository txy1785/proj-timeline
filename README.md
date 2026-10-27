# 项目时间线

面向 10 人以内团队的自托管项目进度管理系统。业务契约见 [`docs/`](docs/)，静态视觉参考见 [`prototype/`](prototype/)。

当前产品决定、实现定位和开发约定见 [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) 与 [`AGENTS.md`](AGENTS.md)。可复用方法、封装与设计模式统一登记在 [`docs/复用方法与设计模式.md`](docs/复用方法与设计模式.md)（写代码前先查）。`PLAN.md` 记录实施历史，其中早期数字属于当时的验证快照。

## 工程结构

- `backend/`：Java 17 + Spring Boot + MySQL API
- `frontend/`：Vue 3 + TypeScript 单页应用
- `docs/database.sql`：数据库契约
- `docs/demo_seed.sql`：仅用于本地演示的可选数据

## 分支与协作

- `main`：版本上线分支；`develop`：开发主线（实时最新）。
- 功能开发从 `develop` 拉出 `feature/<描述>` 分支，完成后由管理者在 GitHub 创建 PR（base 指向 `develop`）。
- 提交代码的 AI 必须用独立会话或子代理开启审核模式，审核通过后合并 `develop`；发布时从 `develop` 合并 `main` 并打 tag。规则详见 [`AGENTS.md`](AGENTS.md) 的「Git 分支与提交流程」。

## 本地启动

1. 创建 MySQL 8 数据库 `proj_timeline` 和具有该库权限的账号。
2. 复制 `.env.example` 中的配置到终端环境变量。
3. 首次启动后端并创建管理员（只执行一次；库中已有 ADMIN 时会拒绝）：

   ```powershell
   mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.arguments=--app.bootstrap-admin=true"
   ```

4. 正常启动后端：`mvn -f backend/pom.xml spring-boot:run`。
5. 安装并启动前端：`npm --prefix frontend install`，然后执行 `npm --prefix frontend run dev`。

开发环境访问 `http://localhost:5173`；开发服务默认监听所有网卡，同一局域网的其他机器可用启动日志打印的 `http://<本机IP>:5173` 访问（`/api` 由 Vite 代理到后端，不需要单独开放后端端口）。后端默认端口 `8080`，而前端代理默认目标是 `58080`；如果后端就用 8080，启动前端前需设置 `API_PROXY_TARGET=http://localhost:8080`，否则接口会 502。完整部署、局域网访问与演示数据说明见 [`docs/部署与运维.md`](docs/部署与运维.md)。

## 验证

```powershell
mvn -f backend/pom.xml test
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run test:e2e
```

