-- Scenario runs and their audit trail (box1-docs#19). Runs change state; the audit trail never changes.
CREATE TABLE scenario_run (
    run_id text PRIMARY KEY CHECK (run_id ~ '^scn_[0-9a-f]{20}$'),
    scenario text NOT NULL,
    title text NOT NULL,
    mode text NOT NULL,
    status text NOT NULL CHECK (status IN ('RUNNING','AWAITING_OPERATOR','VERIFIED','NOT_VERIFIED','FAILED','STOPPED','EXPIRED','ABORTED_RESTART')),
    detail text NULL,
    operator_step text NULL,
    actor text NOT NULL,
    environment text NOT NULL,
    namespace text NOT NULL,
    ttl_seconds integer NOT NULL CHECK (ttl_seconds > 0),
    correlation_id text NOT NULL,
    impact jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    ended_at timestamptz NULL,
    reset_result text NULL CHECK (reset_result IN ('RESET_OK','RESET_FAILED','RESET_GUIDED')),
    reset_detail text NULL,
    reset_at timestamptz NULL
);
-- At most one open run: fixture stock is shared, so runs never overlap.
CREATE UNIQUE INDEX ux_scenario_run_one_open ON scenario_run ((true)) WHERE ended_at IS NULL;
CREATE INDEX ix_scenario_run_started ON scenario_run (started_at DESC);

CREATE TABLE scenario_audit (
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id text NULL REFERENCES scenario_run (run_id),
    scenario text NULL,
    action text NOT NULL,
    outcome text NOT NULL,
    actor text NOT NULL,
    environment text NOT NULL,
    namespace text NOT NULL,
    detail text NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX ix_scenario_audit_run ON scenario_audit (run_id, seq);
CREATE INDEX ix_scenario_audit_time ON scenario_audit (occurred_at);

-- The audit trail is append-only. The only delete allowed is retention: rows older than 90 days.
CREATE FUNCTION scenario_audit_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.occurred_at < now() - interval '90 days' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'scenario_audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER scenario_audit_append_only BEFORE UPDATE OR DELETE ON scenario_audit
    FOR EACH ROW EXECUTE FUNCTION scenario_audit_guard();
CREATE FUNCTION scenario_audit_no_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'scenario_audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER scenario_audit_no_truncate BEFORE TRUNCATE ON scenario_audit
    FOR EACH STATEMENT EXECUTE FUNCTION scenario_audit_no_truncate();
