SET NAMES utf8mb4;

-- V17：工作项只要求标题；计划日期与负责人可暂缺（待排期/未指派），chk_node_dates 对 NULL 不生效，两端都有时仍须有序。
-- historical 标记实际日期来自补录或人工日期纠正：PM/ADMIN 补录已在进行/已完成的工作项，或经纠错接口更正实际日期。
ALTER TABLE nodes
  MODIFY COLUMN plan_start DATE NULL COMMENT '计划开始日期；可空表示待排期',
  MODIFY COLUMN plan_end DATE NULL COMMENT '计划完成日期；可空表示待排期',
  MODIFY COLUMN owner_id BIGINT UNSIGNED NULL COMMENT '工作项负责人；可空表示未指派',
  ADD COLUMN historical TINYINT(1) NOT NULL DEFAULT 0 COMMENT '实际日期来自补录或人工日期纠正' AFTER status;
