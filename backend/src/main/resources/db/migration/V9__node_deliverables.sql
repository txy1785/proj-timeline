SET NAMES utf8mb4;

-- 节点交付物清单：回答"这个节点要交什么、还缺什么、文件在哪"。
-- 文件仍然复用 attachments（owner_type='NODE' 的节点文件），清单项只可选关联其中一个，
-- 不新建文件体系、不做跨节点依赖、不记录"需要/产出"方向（归属已由 node_id 表达）。
CREATE TABLE node_deliverables (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id       BIGINT UNSIGNED NOT NULL,
  project_id    BIGINT UNSIGNED NOT NULL COMMENT '冗余项目 id，便于按项目汇总与权限校验',
  name          VARCHAR(200) NOT NULL COMMENT '交付物名称，如"接口文档 v1"',
  status        ENUM('WANTED','PROVIDED') NOT NULL DEFAULT 'WANTED' COMMENT 'WANTED 待提供 / PROVIDED 已提供',
  owner_id      BIGINT UNSIGNED NULL COMMENT '负责人，为空表示未指定',
  remark        VARCHAR(500) NULL COMMENT '备注，如资料在外部系统的位置',
  attachment_id BIGINT UNSIGNED NULL COMMENT '可选关联文件，必须是同一节点下的附件',
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
) ENGINE=InnoDB COMMENT='节点交付物清单';

-- 交付物新增/编辑/删除使用独立审计动作，避免复用 TASK_* 造成误读。
ALTER TABLE node_logs
  MODIFY COLUMN action ENUM('CREATE','STATUS_CHANGE','PROGRESS_UPDATE','BLOCK','UNBLOCK','OVERDUE_REASON','COMPLETE','CANCEL','RESCHEDULE','TASK_ADD','TASK_STATUS','TASK_UPDATE','TASK_DELETE','WORK_LOG_ADD','WORK_LOG_UPDATE','WORK_LOG_DELETE','DELIVERABLE_ADD','DELIVERABLE_UPDATE','DELIVERABLE_DELETE') NOT NULL;
