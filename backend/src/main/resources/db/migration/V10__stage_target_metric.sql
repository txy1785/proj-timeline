SET NAMES utf8mb4;

-- 阶段性目标的可选量化指标：把「带日期的里程碑」扩展成「能回答达成多少」的目标。
-- 四列全空 = 保持原来的日期型里程碑；不引入 Objective/KeyResult、周期、评分或对齐。
-- 目标值必须大于 0（避免除零）；当前值按 0 处理；进度由服务端实时派生，不落库。
ALTER TABLE project_stage_targets
  ADD COLUMN metric_name    VARCHAR(100)  NULL COMMENT '指标名称，如“上线模块数”；为空表示未量化' AFTER description,
  ADD COLUMN metric_unit    VARCHAR(20)   NULL COMMENT '指标单位，如“个”“万元”' AFTER metric_name,
  ADD COLUMN metric_target  DECIMAL(12,2) NULL COMMENT '目标值，必须大于 0' AFTER metric_unit,
  ADD COLUMN metric_current DECIMAL(12,2) NULL COMMENT '当前值，空按 0 计算' AFTER metric_target;
