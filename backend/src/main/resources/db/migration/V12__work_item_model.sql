SET NAMES utf8mb4;

-- 节点升级为「工作项」；项目级工作项不属于任何功能模块（module_id 为空）。
ALTER TABLE nodes
  MODIFY COLUMN module_id BIGINT UNSIGNED NULL COMMENT '所属功能模块；项目级工作项为空',
  ADD COLUMN type ENUM('FEATURE','TEST','MAINTENANCE','BUGFIX','OTHER') NOT NULL DEFAULT 'FEATURE'
    COMMENT '工作项类型：功能开发/测试/维护/缺陷修复/其他' AFTER module_id;

-- 任务类型与缺陷/修复溯源
ALTER TABLE tasks
  ADD COLUMN kind ENUM('FEATURE','FIX','BUG','TEST','OTHER') NOT NULL DEFAULT 'FEATURE'
    COMMENT '任务类型：功能/修复/缺陷/测试/其他' AFTER description,
  ADD COLUMN related_node_id BIGINT UNSIGNED NULL
    COMMENT '关联的原工作项（缺陷/修复溯源）' AFTER kind,
  ADD KEY idx_task_kind (kind),
  ADD KEY idx_task_related_node (related_node_id),
  ADD CONSTRAINT fk_tasks_related_node FOREIGN KEY (related_node_id) REFERENCES nodes(id);
