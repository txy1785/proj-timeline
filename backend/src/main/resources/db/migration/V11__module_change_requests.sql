SET NAMES utf8mb4;

-- 模块变更申请：没有模块编辑权限的项目成员，对「名称 / 描述 / 负责人」提出变更申请，由 PM 审批后自动生效。
-- 申请内容按列存（不用 JSON），便于列表直接展示与审计；同一模块同时只允许一个待审批申请。
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

-- 通知新增两类：待审批（→PM）与审批结果（→申请人）；在原枚举末尾追加，避免重建历史值。
ALTER TABLE notifications
  MODIFY COLUMN type ENUM('OVERDUE_ALERT','BLOCK_ALERT','TASK_ASSIGN','NODE_DONE','WEEKLY_PUBLISHED','CHANGE_REQUEST_SUBMITTED','CHANGE_REQUEST_DECIDED') NOT NULL;
