CREATE TABLE project_stage_targets (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id  BIGINT UNSIGNED NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description TEXT NULL,
  target_date DATE NOT NULL,
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_project_target_date (project_id, target_date, sort_order),
  CONSTRAINT fk_stage_targets_project FOREIGN KEY (project_id) REFERENCES projects(id)
) ENGINE=InnoDB COMMENT='项目阶段性目标';

ALTER TABLE modules
  DROP CHECK chk_module_dates,
  DROP INDEX idx_project_stage_dates,
  DROP COLUMN plan_end,
  DROP COLUMN plan_start;
