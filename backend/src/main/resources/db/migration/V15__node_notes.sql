SET NAMES utf8mb4;

-- 工作项进展记录：与 task_notes 同一形态（命名的 Markdown 文件，可再编辑），归属工作项。
-- 正文里粘贴的文件以 attachment:{id} 引用工作项附件（owner_type=NODE，分类 NODE_RECORD）。
CREATE TABLE node_notes (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  node_id    BIGINT UNSIGNED NOT NULL,
  title      VARCHAR(200) NOT NULL DEFAULT '进展记录.md' COMMENT '记录名称，按 .md 文件名保存',
  author_id  BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NULL COMMENT '最后编辑人，未编辑过为空',
  content    MEDIUMTEXT NOT NULL COMMENT 'Markdown 正文，粘贴的附件以 attachment:{id} 占位',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NULL COMMENT '最后编辑时间，未编辑过为空',
  PRIMARY KEY (id),
  KEY idx_node_notes_node (node_id, created_at),
  CONSTRAINT fk_node_notes_node FOREIGN KEY (node_id) REFERENCES nodes(id),
  CONSTRAINT fk_node_notes_author FOREIGN KEY (author_id) REFERENCES users(id),
  CONSTRAINT fk_node_notes_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='工作项进展记录（命名的 Markdown 文件，可再编辑）';
