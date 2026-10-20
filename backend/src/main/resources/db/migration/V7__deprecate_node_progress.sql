-- V7：节点进度改为由任务完成率实时派生（见 ProgressRules），不再手动填写。
-- 保留 nodes.progress 列与历史值，避免删列丢数据；新代码不读、不写该列。
ALTER TABLE nodes
  MODIFY COLUMN progress TINYINT UNSIGNED NOT NULL DEFAULT 0
  COMMENT '已弃用（V7 起）：节点进度由任务完成率派生，不再读写该列';
