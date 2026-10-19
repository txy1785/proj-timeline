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

-- 项目阶段性目标（直接属于项目，与模块无关）
CREATE TABLE project_stage_targets (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id  BIGINT UNSIGNED NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description TEXT NULL,
  target_date DATE NOT NULL,
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_target_date (project_id, target_date, sort_order),
  CONSTRAINT fk_stage_targets_project FOREIGN KEY (project_id) REFERENCES projects(id)
) ENGINE=InnoDB COMMENT='项目阶段性目标';

-- ------------------------------------------------------------
-- 3. 项目成员（项目内职务：PM 项目经理 / MEMBER 成员）
--    模块负责人、节点负责人不在此表，由 modules.owner_id / nodes.owner_id 表达
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
--    模块负责人 = 新建节点时的默认负责人
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
-- 5. 节点（里程碑）
--    状态 4 态；受阻/延期为可叠加标记。
--    延期与临近到期按状态、计划结束日和业务日期计算，不保存过期布尔值。
-- ------------------------------------------------------------
CREATE TABLE nodes (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id    BIGINT UNSIGNED NOT NULL,
  module_id     BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(200) NOT NULL,
  description   TEXT NULL,
  plan_start    DATE NOT NULL,
  plan_end      DATE NOT NULL,
  actual_start  DATE NULL,
  actual_end    DATE NULL,
  status        ENUM('NOT_STARTED','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'NOT_STARTED',
  progress      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '手动百分比 0-100，DONE 时强制 100',
  blocked       TINYINT(1) NOT NULL DEFAULT 0,
  blocked_reason TEXT NULL,
  overdue_reason TEXT NULL,
  owner_id      BIGINT UNSIGNED NOT NULL COMMENT '节点负责人（默认继承模块负责人）',
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
) ENGINE=InnoDB COMMENT='节点（里程碑）';

-- ------------------------------------------------------------
-- 6. 任务（挂节点下，跨项目统计通过节点关联）
-- ------------------------------------------------------------
CREATE TABLE tasks (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id       BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(200) NOT NULL,
  description   TEXT NULL,
  assignee_id   BIGINT UNSIGNED NOT NULL COMMENT '执行人（默认继承节点负责人）',
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
  CONSTRAINT fk_tasks_node FOREIGN KEY (node_id) REFERENCES nodes(id),
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
-- 8. 节点日志（周报草稿聚合 + 审计的数据来源）
-- ------------------------------------------------------------
CREATE TABLE node_logs (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id    BIGINT UNSIGNED NOT NULL,
  user_id    BIGINT UNSIGNED NULL,
  action     ENUM('CREATE','STATUS_CHANGE','PROGRESS_UPDATE','BLOCK','UNBLOCK','OVERDUE_REASON','COMPLETE','CANCEL','RESCHEDULE','TASK_ADD','TASK_STATUS','WORK_LOG_ADD','WORK_LOG_UPDATE','WORK_LOG_DELETE') NOT NULL,
  detail     VARCHAR(500) NOT NULL,
  before_value JSON NULL COMMENT '变更前结构化值；创建事件可为空',
  after_value  JSON NULL COMMENT '变更后结构化值；创建事件可为空',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_node_time (node_id, created_at),
  CONSTRAINT fk_logs_node FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_logs_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='节点变更日志';

-- ------------------------------------------------------------
-- 9. 站内通知
-- ------------------------------------------------------------
CREATE TABLE notifications (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id    BIGINT UNSIGNED NOT NULL COMMENT '接收人',
  type       ENUM('OVERDUE_ALERT','BLOCK_ALERT','TASK_ASSIGN','NODE_DONE','WEEKLY_PUBLISHED') NOT NULL,
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
-- 11. 文档与附件（Flyway V4）
-- ------------------------------------------------------------
CREATE TABLE attachments (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id    BIGINT UNSIGNED NOT NULL,
  owner_type    VARCHAR(16) NOT NULL COMMENT 'PROJECT / MODULE，预留 NODE / TASK',
  owner_id      BIGINT UNSIGNED NOT NULL,
  category      VARCHAR(40) NOT NULL COMMENT 'DocumentCatalog 分类编码',
  title         VARCHAR(200) NOT NULL COMMENT '文件标题，同名再上传时版本号递增',
  original_name VARCHAR(255) NOT NULL,
  stored_key    VARCHAR(255) NOT NULL COMMENT '存储相对路径，不含根目录',
  content_type  VARCHAR(160) NULL,
  size_bytes    BIGINT NOT NULL DEFAULT 0,
  sha256        CHAR(64) NULL,
  version       INT NOT NULL DEFAULT 1,
  remark        VARCHAR(500) NULL,
  uploader_id   BIGINT UNSIGNED NOT NULL,
  deleted       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除，磁盘文件保留以维持痕迹',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_attachment_owner (owner_type, owner_id, category),
  KEY idx_attachment_project (project_id, created_at),
  CONSTRAINT fk_attachment_project FOREIGN KEY (project_id) REFERENCES projects(id)
) ENGINE=InnoDB COMMENT='项目与模块的文档、附件';

