-- Projects always have a start date. Their completion date remains optional.
UPDATE projects
SET plan_start = DATE(created_at)
WHERE plan_start IS NULL;

ALTER TABLE projects
  MODIFY plan_start DATE NOT NULL;

-- A module is a project stage with a required start and target completion date.
ALTER TABLE modules
  ADD COLUMN plan_start DATE NULL AFTER description,
  ADD COLUMN plan_end DATE NULL AFTER plan_start;

UPDATE modules m
JOIN projects p ON p.id = m.project_id
LEFT JOIN (
  SELECT module_id, MIN(plan_start) AS first_start, MAX(plan_end) AS last_end
  FROM nodes
  GROUP BY module_id
) bounds ON bounds.module_id = m.id
SET m.plan_start = COALESCE(bounds.first_start, p.plan_start),
    m.plan_end = COALESCE(bounds.last_end, p.plan_end, p.plan_start);

ALTER TABLE modules
  MODIFY plan_start DATE NOT NULL,
  MODIFY plan_end DATE NOT NULL,
  ADD KEY idx_project_stage_dates (project_id, plan_start, plan_end),
  ADD CONSTRAINT chk_module_dates CHECK (plan_start <= plan_end);
