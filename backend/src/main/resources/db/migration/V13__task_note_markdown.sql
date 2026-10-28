SET NAMES utf8mb4;

-- 进展记录升级为「可命名的 Markdown 文件」：title 即记录名（.md 文件名），content 保存 Markdown 正文。
-- 正文里粘贴的附件以 attachment:{id} 引用，文件本身仍走 attachments（owner_type=TASK），不在备注里重复存文件。
ALTER TABLE task_notes
  ADD COLUMN title VARCHAR(200) NOT NULL DEFAULT '进展记录.md' COMMENT '进展记录名称，按 .md 文件名保存' AFTER task_id,
  MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT 'Markdown 正文，粘贴的附件以 attachment:{id} 占位';
