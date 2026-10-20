SET NAMES utf8mb4;

-- 任务进展记录：记录执行人对"怎么做的、结果如何"的补充，可当备注用，按时间追加。
CREATE TABLE task_notes (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id    BIGINT UNSIGNED NOT NULL,
  author_id  BIGINT UNSIGNED NOT NULL,
  content    VARCHAR(2000) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_notes_task (task_id, created_at),
  CONSTRAINT fk_task_notes_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_task_notes_author FOREIGN KEY (author_id) REFERENCES users(id)
) ENGINE=InnoDB COMMENT='任务进展记录';

-- 任务可以挂附件，复用 attachments 的多态归属
ALTER TABLE attachments
  MODIFY owner_type VARCHAR(16) NOT NULL COMMENT 'PROJECT / MODULE / NODE / TASK';
