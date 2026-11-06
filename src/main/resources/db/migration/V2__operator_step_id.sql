-- The machine-readable ID of the operator step a run waits for, so a confirmation can name exactly that step.
ALTER TABLE scenario_run ADD COLUMN operator_step_id text NULL;
