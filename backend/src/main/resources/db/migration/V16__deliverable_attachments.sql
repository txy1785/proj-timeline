SET NAMES utf8mb4;

-- V16：一条交付记录改为可关联多个工作项文件，按 V7 的"弃用而不删列"先例保留 node_deliverables.attachment_id。
-- 关联关系单独成表，文件仍然复用 attachments 里的工作项级附件（owner_type='NODE'），不新建文件体系。
CREATE TABLE node_deliverable_attachments (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  deliverable_id BIGINT UNSIGNED NOT NULL,
  attachment_id  BIGINT UNSIGNED NOT NULL,
  sort_order     INT NOT NULL DEFAULT 0,
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_deliverable_attachment (deliverable_id, attachment_id),
  KEY idx_deliverable_attachment_ref (attachment_id),
  CONSTRAINT fk_dda_deliverable FOREIGN KEY (deliverable_id) REFERENCES node_deliverables(id),
  CONSTRAINT fk_dda_attachment FOREIGN KEY (attachment_id) REFERENCES attachments(id)
) ENGINE=InnoDB COMMENT='交付物关联文件（一条交付记录可关联多个工作项文件）';

-- 回填历史单文件关联：已有的 attachment_id 变成一条关联行，阅读时顺序不变。
INSERT INTO node_deliverable_attachments(deliverable_id, attachment_id, sort_order)
SELECT id, attachment_id, 0 FROM node_deliverables WHERE attachment_id IS NOT NULL;

-- 保留列与历史值（不删列、不丢数据），仅标注弃用；新代码不读不写该列。
ALTER TABLE node_deliverables
  MODIFY COLUMN attachment_id BIGINT UNSIGNED NULL
  COMMENT '已弃用（V16 起）：交付物可关联多个文件，改用 node_deliverable_attachments；新代码不读不写';
