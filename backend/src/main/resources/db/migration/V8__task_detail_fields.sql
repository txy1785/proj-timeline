SET NAMES utf8mb4;

-- 任务补充两个说明字段：任务情况（当前进展/问题）与处理方式（准备怎么处理/已采取措施）。
ALTER TABLE tasks
  ADD COLUMN situation TEXT NULL COMMENT '任务情况：当前进展、问题或背景' AFTER description,
  ADD COLUMN handling  TEXT NULL COMMENT '处理方式：准备怎么处理或已采取的措施' AFTER situation;

-- 删除/编辑任务使用独立审计动作，避免复用 TASK_STATUS 造成误导。
ALTER TABLE node_logs
  MODIFY COLUMN action ENUM('CREATE','STATUS_CHANGE','PROGRESS_UPDATE','BLOCK','UNBLOCK','OVERDUE_REASON','COMPLETE','CANCEL','RESCHEDULE','TASK_ADD','TASK_STATUS','TASK_UPDATE','TASK_DELETE','WORK_LOG_ADD','WORK_LOG_UPDATE','WORK_LOG_DELETE') NOT NULL;
