SET NAMES utf8mb4;

-- 进展记录支持再编辑：记录最后编辑人与时间；未编辑过的旧记录两列保持 NULL。
ALTER TABLE task_notes
  ADD COLUMN updated_by BIGINT UNSIGNED NULL COMMENT '最后编辑人' AFTER author_id,
  ADD COLUMN updated_at DATETIME NULL COMMENT '最后编辑时间' AFTER created_at;

ALTER TABLE task_notes
  ADD CONSTRAINT fk_task_notes_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
