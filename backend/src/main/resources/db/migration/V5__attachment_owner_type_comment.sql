-- attachments 自 V4 起也支持节点级文件（owner_type = NODE）。
-- V4 已应用到部分环境，不能改写其内容；这里只修正列注释，不改变列定义与数据。
ALTER TABLE attachments
  MODIFY owner_type VARCHAR(16) NOT NULL COMMENT 'PROJECT / MODULE / NODE，预留 TASK';
