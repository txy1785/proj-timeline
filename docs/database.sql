-- ============================================================
-- 项目时间线（proj-timeline）· 数据库设计 v0.1
-- 目标库：MySQL 8.0 ｜ 字符集 utf8mb4
-- 与 docs/需求文档与产品设计方案.md §6.2 对应。
-- 本文件只定义表结构；演示数据单独放在 docs/demo_seed.sql。
-- ============================================================
CREATE DATABASE IF NOT EXISTS proj_timeline DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE proj_timeline;

-- ------------------------------------------------------------
-- 1. 用户（全局角色：ADMIN 管理员 / USER 普通用户 / VIEWER 只读观察者）
-- ------------------------------------------------------------
CREATE TABLE users (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(50)  NOT NULL COMMENT '登录名',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，生产环境禁止明文',
  name          VARCHAR(50)  NOT NULL COMMENT '显示姓名',
  email         VARCHAR(100) NULL,
  global_role   ENUM('ADMIN','USER','VIEWER') NOT NULL DEFAULT 'USER',
  active        TINYINT(1)   NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='用户';

-- ------------------------------------------------------------
-- 2. 项目
-- ------------------------------------------------------------
CREATE TABLE projects (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(100) NOT NULL,
  code        VARCHAR(50)  NOT NULL COMMENT '项目代号',
  description TEXT         NULL,
  plan_start  DATE         NOT NULL,
  plan_end    DATE         NULL,
  status      ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE',
  created_by  BIGINT UNSIGNED NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  CONSTRAINT fk_projects_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_project_dates CHECK (plan_end IS NULL OR plan_start <= plan_end)
) ENGINE=InnoDB COMMENT='项目';

-- 项目阶段性目标（直接属于项目，与模块无关；V10 增加可选量化指标，四列全空表示日期型里程碑）
CREATE TABLE project_stage_targets (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id     BIGINT UNSIGNED NOT NULL,
  name           VARCHAR(100) NOT NULL,
  description    TEXT NULL,
  metric_name    VARCHAR(100)  NULL COMMENT '指标名称，如“上线模块数”；为空表示未量化',
  metric_unit    VARCHAR(20)   NULL COMMENT '指标单位，如“个”“万元”',
  metric_target  DECIMAL(12,2) NULL COMMENT '目标值，必须大于 0',
  metric_current DECIMAL(12,2) NULL COMMENT '当前值，空按 0 计算',
  target_date    DATE NOT NULL,
  sort_order     INT NOT NULL DEFAULT 0,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_target_date (project_id, target_date, sort_order),
  CONSTRAINT fk_stage_targets_project FOREIGN KEY (project_id) REFERENCES projects(id)
) ENGINE=InnoDB COMMENT='项目阶段性目标';

-- ------------------------------------------------------------
-- 3. 项目成员（项目内职务：PM 项目经理 / MEMBER 成员）
--    模块负责人、工作项负责人不在此表，由 modules.owner_id / nodes.owner_id 表达
-- ------------------------------------------------------------
CREATE TABLE project_members (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id   BIGINT UNSIGNED NOT NULL,
  user_id      BIGINT UNSIGNED NOT NULL,
  project_role ENUM('PM','MEMBER') NOT NULL DEFAULT 'MEMBER',
  pm_project_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN project_role = 'PM' THEN project_id ELSE NULL END) STORED,
  joined_at    DATE NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_user (project_id, user_id),
  KEY idx_user (user_id),
  UNIQUE KEY uk_one_pm_per_project (pm_project_id),
  CONSTRAINT fk_members_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='项目成员';

-- ------------------------------------------------------------
-- 4. 模块（v1 一层；parent_id 预留多级树）
--    模块负责人 = 新建工作项时的默认负责人
-- ------------------------------------------------------------
CREATE TABLE modules (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id  BIGINT UNSIGNED NOT NULL,
  parent_id   BIGINT UNSIGNED NULL COMMENT '预留：父模块（多级树）',
  name        VARCHAR(100) NOT NULL,
  description TEXT NULL,
  owner_id    BIGINT UNSIGNED NOT NULL COMMENT '模块负责人',
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project (project_id),
  UNIQUE KEY uk_module_project (id, project_id),
  KEY idx_owner (owner_id),
  CONSTRAINT fk_modules_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_modules_parent FOREIGN KEY (parent_id) REFERENCES modules(id),
  CONSTRAINT fk_modules_owner FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='模块';

-- ------------------------------------------------------------
-- 5. 工作项（原“节点”；模块内交付单元，也可为项目级）
--    状态 4 态；受阻/延期为可叠加标记。
--    延期与临近到期按状态、计划结束日和业务日期计算，不保存过期布尔值。
-- ------------------------------------------------------------
CREATE TABLE nodes (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id    BIGINT UNSIGNED NOT NULL,
  module_id     BIGINT UNSIGNED NULL COMMENT '所属功能模块；项目级工作项为空',
  type          ENUM('FEATURE','TEST','MAINTENANCE','BUGFIX','OTHER') NOT NULL DEFAULT 'FEATURE' COMMENT '工作项类型',
  name          VARCHAR(200) NOT NULL,
  description   TEXT NULL,
  plan_start    DATE NOT NULL,
  plan_end      DATE NOT NULL,
  actual_start  DATE NULL,
  actual_end    DATE NULL,
  status        ENUM('NOT_STARTED','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'NOT_STARTED',
  progress      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已弃用（V7 起）：工作项进度由任务完成率派生，不再读写该列',
  blocked       TINYINT(1) NOT NULL DEFAULT 0,
  blocked_reason TEXT NULL,
  overdue_reason TEXT NULL,
  owner_id      BIGINT UNSIGNED NOT NULL COMMENT '工作项负责人（默认继承模块负责人）',
  sort_order    INT NOT NULL DEFAULT 0,
  created_by    BIGINT UNSIGNED NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project (project_id),
  KEY idx_module (module_id),
  KEY idx_owner (owner_id),
  KEY idx_status (status),
  KEY idx_status_plan_end (status, plan_end),
  CONSTRAINT fk_nodes_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_nodes_module_project FOREIGN KEY (module_id, project_id) REFERENCES modules(id, project_id),
  CONSTRAINT fk_nodes_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_nodes_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_node_dates CHECK (plan_start <= plan_end),
  CONSTRAINT chk_node_progress CHECK (progress <= 100)
) ENGINE=InnoDB COMMENT='工作项（里程碑）';

-- ------------------------------------------------------------
-- 6. 任务（挂工作项下，跨项目统计通过工作项关联）
-- ------------------------------------------------------------
CREATE TABLE tasks (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id       BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(200) NOT NULL,
  description   TEXT NULL,
  kind          ENUM('FEATURE','FIX','BUG','TEST','OTHER') NOT NULL DEFAULT 'FEATURE' COMMENT '任务类型：功能/修复/缺陷/测试/其他',
  related_node_id BIGINT UNSIGNED NULL COMMENT '关联的原工作项（缺陷/修复溯源）',
  situation     TEXT NULL COMMENT '任务情况：当前进展、问题或背景',
  handling      TEXT NULL COMMENT '处理方式：准备怎么处理或已采取的措施',
  assignee_id   BIGINT UNSIGNED NOT NULL COMMENT '执行人（默认继承工作项负责人）',
  status        ENUM('TODO','DOING','DONE') NOT NULL DEFAULT 'TODO',
  due_date      DATE NULL,
  estimate_days DECIMAL(5,1) NULL COMMENT '计划人天',
  done_at       DATETIME NULL,
  created_by    BIGINT UNSIGNED NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_node (node_id),
  KEY idx_assignee (assignee_id),
  KEY idx_status (status),
  KEY idx_due (due_date),
  KEY idx_task_kind (kind),
  KEY idx_task_related_node (related_node_id),
  CONSTRAINT fk_tasks_node FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_tasks_related_node FOREIGN KEY (related_node_id) REFERENCES nodes(id),
  CONSTRAINT fk_tasks_assignee FOREIGN KEY (assignee_id) REFERENCES users(id),
  CONSTRAINT fk_tasks_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_task_estimate CHECK (estimate_days IS NULL OR estimate_days >= 0)
) ENGINE=InnoDB COMMENT='任务';

-- ------------------------------------------------------------
-- 7. 逐日工时：一名成员对一个任务在同一天最多一条记录
-- ------------------------------------------------------------
CREATE TABLE task_work_logs (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id    BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NOT NULL,
  work_date  DATE NOT NULL,
  days       DECIMAL(5,1) NOT NULL COMMENT '实际人天，必须大于 0',
  note       VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_user_date (task_id, user_id, work_date),
  KEY idx_work_date (work_date),
  KEY idx_work_user_date (user_id, work_date),
  CONSTRAINT fk_work_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_work_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT chk_work_days CHECK (days > 0)
) ENGINE=InnoDB COMMENT='任务逐日工时';

-- ------------------------------------------------------------
-- 8. 工作项日志（周报草稿聚合 + 审计的数据来源）
-- ------------------------------------------------------------
CREATE TABLE node_logs (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id    BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NULL,
  action     ENUM('CREATE','STATUS_CHANGE','PROGRESS_UPDATE','BLOCK','UNBLOCK','OVERDUE_REASON','COMPLETE','CANCEL','RESCHEDULE','TASK_ADD','TASK_STATUS','TASK_UPDATE','TASK_DELETE','WORK_LOG_ADD','WORK_LOG_UPDATE','WORK_LOG_DELETE','DELIVERABLE_ADD','DELIVERABLE_UPDATE','DELIVERABLE_DELETE') NOT NULL,
  detail     VARCHAR(500) NOT NULL,
  before_value JSON NULL COMMENT '变更前结构化值；创建事件可为空',
  after_value  JSON NULL COMMENT '变更后结构化值；创建事件可为空',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_node_time (node_id, created_at),
  CONSTRAINT fk_logs_node FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_logs_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='工作项变更日志';

-- ------------------------------------------------------------
-- 9. 站内通知
-- ------------------------------------------------------------
CREATE TABLE notifications (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL COMMENT '接收人',
  type       ENUM('OVERDUE_ALERT','BLOCK_ALERT','TASK_ASSIGN','NODE_DONE','WEEKLY_PUBLISHED','CHANGE_REQUEST_SUBMITTED','CHANGE_REQUEST_DECIDED') NOT NULL,
  title      VARCHAR(200) NOT NULL,
  content    VARCHAR(500) NULL,
  link       VARCHAR(200) NULL COMMENT '跳转（如 /node/123）',
  read_at    DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_user_read (user_id, read_at),
  CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='站内通知';

-- ------------------------------------------------------------
-- 10. 周报
-- ------------------------------------------------------------
CREATE TABLE weekly_reports (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id   BIGINT UNSIGNED NOT NULL,
  week_start   DATE NOT NULL,
  week_end     DATE NOT NULL,
  content      JSON NULL COMMENT '自动汇总快照：完成/延期/受阻/下周到期/新增/工时',
  note         TEXT NULL COMMENT 'PM 补充说明',
  status       ENUM('DRAFT','PUBLISHED') NOT NULL DEFAULT 'DRAFT',
  created_by   BIGINT UNSIGNED NULL,
  published_at DATETIME NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_week (project_id, week_start),
  CONSTRAINT fk_reports_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_reports_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT chk_report_week CHECK (week_start <= week_end)
) ENGINE=InnoDB COMMENT='周报';

-- ------------------------------------------------------------
-- 11. 文档与附件（Flyway V4 建表；V5 修正 owner_type 注释；V6 加入 TASK，此处为最终态）
-- ------------------------------------------------------------
CREATE TABLE attachments (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id    BIGINT UNSIGNED NOT NULL,
  owner_type    VARCHAR(16) NOT NULL COMMENT 'PROJECT / MODULE / NODE / TASK',
  owner_id      BIGINT UNSIGNED NOT NULL,
  category      VARCHAR(40) NOT NULL COMMENT 'DocumentCatalog 分类编码',
  title         VARCHAR(200) NOT NULL COMMENT '文件标题，同名再上传时版本号递增',
  original_name VARCHAR(255) NOT NULL,
  stored_key    VARCHAR(255) NOT NULL COMMENT '存储相对路径，不含根目录',
  content_type  VARCHAR(160) NULL COMMENT '服务端按扩展名推导，不采用客户端声明',
  size_bytes    BIGINT NOT NULL DEFAULT 0,
  sha256        CHAR(64) NULL,
  version       INT NOT NULL DEFAULT 1,
  remark        VARCHAR(500) NULL,
  uploader_id   BIGINT UNSIGNED NOT NULL,
  deleted       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除，磁盘文件保留以维持痕迹',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attachment_version (owner_type, owner_id, title, version),
  KEY idx_attachment_owner (owner_type, owner_id, category),
  KEY idx_attachment_project (project_id, created_at),
  CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='项目、模块、工作项与任务的文档附件';

-- ------------------------------------------------------------
-- 12. 任务进展记录（Flyway V6，V13 升级为命名的 Markdown 文件，V14 支持再编辑）
--     每行是一份 .md 记录：title 为记录名/文件名，content 为 Markdown 正文；
--     正文里的 attachment:{id} 指向本任务的普通附件（分类 TASK_RECORD），不重复存文件。
-- ------------------------------------------------------------
CREATE TABLE task_notes (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id    BIGINT UNSIGNED NOT NULL,
  title      VARCHAR(200) NOT NULL DEFAULT '进展记录.md' COMMENT '进展记录名称，按 .md 文件名保存',
  author_id  BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NULL COMMENT '最后编辑人，未编辑过为空',
  content    MEDIUMTEXT NOT NULL COMMENT 'Markdown 正文，粘贴的附件以 attachment:{id} 占位',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NULL COMMENT '最后编辑时间，未编辑过为空',
  PRIMARY KEY (id),
  KEY idx_task_notes_task (task_id, created_at),
  CONSTRAINT fk_task_notes_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_task_notes_author FOREIGN KEY (author_id) REFERENCES users(id),
  CONSTRAINT fk_task_notes_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='任务进展记录（命名的 Markdown 文件，可再编辑）';

-- ------------------------------------------------------------
-- 13. 工作项交付物清单（Flyway V9）
--     回答"这个工作项要交什么、还缺什么、文件在哪"；文件复用 attachments 的工作项级文件，
--     清单项只可选关联一个同工作项附件，不新建文件体系、不做跨工作项依赖。
-- ------------------------------------------------------------
CREATE TABLE node_deliverables (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id       BIGINT UNSIGNED NOT NULL,
  project_id    BIGINT UNSIGNED NOT NULL COMMENT '冗余项目 id，便于按项目汇总与权限校验',
  name          VARCHAR(200) NOT NULL COMMENT '交付物名称，如"接口文档 v1"',
  status        ENUM('WANTED','PROVIDED') NOT NULL DEFAULT 'WANTED' COMMENT 'WANTED 待提供 / PROVIDED 已提供',
  owner_id      BIGINT UNSIGNED NULL COMMENT '负责人，为空表示未指定',
  remark        VARCHAR(500) NULL COMMENT '备注，如资料在外部系统的位置',
  attachment_id BIGINT UNSIGNED NULL COMMENT '可选关联文件，必须是同一工作项下的附件',
  sort_order    INT NOT NULL DEFAULT 0,
  created_by    BIGINT UNSIGNED NULL,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_deliverable_node (node_id, sort_order, id),
  KEY idx_deliverable_project (project_id),
  KEY idx_deliverable_status (status),
  CONSTRAINT fk_deliverable_node FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_deliverable_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_deliverable_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_deliverable_attachment FOREIGN KEY (attachment_id) REFERENCES attachments(id),
  CONSTRAINT fk_deliverable_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='工作项交付物清单';

-- ------------------------------------------------------------
-- 14. 模块变更申请（Flyway V11）
--     没有模块编辑权限的成员对「名称/描述/负责人」提申请，PM 审批通过后由服务端自动改模块。
--     pending_module_id 是生成列 + 唯一键，保证同一模块同时只有一个待审批申请。
-- ------------------------------------------------------------
CREATE TABLE module_change_requests (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id        BIGINT UNSIGNED NOT NULL,
  module_id         BIGINT UNSIGNED NOT NULL,
  requested_by      BIGINT UNSIGNED NOT NULL,
  name              VARCHAR(100) NOT NULL COMMENT '申请生效后的模块名称',
  description       TEXT NULL,
  owner_id          BIGINT UNSIGNED NOT NULL COMMENT '申请生效后的模块负责人',
  reason            VARCHAR(500) NULL COMMENT '申请说明',
  status            ENUM('PENDING','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  pending_module_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(status='PENDING', module_id, NULL)) STORED COMMENT '保证同一模块同时只有一个待审批申请',
  decided_by        BIGINT UNSIGNED NULL,
  decided_at        DATETIME NULL,
  decision_remark   VARCHAR(500) NULL COMMENT '审批意见',
  created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_request_pending_module (pending_module_id),
  KEY idx_request_project (project_id, status, created_at),
  KEY idx_request_module (module_id),
  KEY idx_request_user (requested_by, created_at),
  CONSTRAINT fk_request_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_request_module FOREIGN KEY (module_id) REFERENCES modules(id),
  CONSTRAINT fk_request_user FOREIGN KEY (requested_by) REFERENCES users(id),
  CONSTRAINT fk_request_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_request_decider FOREIGN KEY (decided_by) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='模块变更申请';

