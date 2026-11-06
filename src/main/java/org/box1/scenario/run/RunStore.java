package org.box1.scenario.run;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;

/** Run and audit SQL. Each statement commits on its own; the unique open-run index serialises starts. */
@ApplicationScoped
public class RunStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN_COLUMNS = "run_id, scenario, title, mode, status, detail, operator_step, actor, environment,"
            + " namespace, ttl_seconds, correlation_id, impact::text, started_at, expires_at, ended_at, reset_result,"
            + " reset_detail, reset_at";

    private final DataSource dataSource;

    public RunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A database failure. The message is for logs, never for callers. */
    public static class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StoreException(SQLException cause) {
            super("Audit store failure: " + cause.getSQLState(), cause);
        }
    }

    public record Run(String runId, String scenario, String title, String mode, RunState status, String detail,
            String operatorStep, String actor, String environment, String namespace, int ttlSeconds, String correlationId,
            JsonNode impact, Instant startedAt, Instant expiresAt, Instant endedAt, String resetResult, String resetDetail,
            Instant resetAt) {}

    public record Audit(long seq, String runId, String scenario, String action, String outcome, String actor,
            String environment, String namespace, String detail, Instant occurredAt) {}

    /** Insert a run. False when another run is still open. */
    public boolean insert(Run run) {
        String sql = "INSERT INTO scenario_run (run_id, scenario, title, mode, status, actor, environment, namespace, ttl_seconds,"
                + " correlation_id, started_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, run.runId());
            s.setString(2, run.scenario());
            s.setString(3, run.title());
            s.setString(4, run.mode());
            s.setString(5, run.status().name());
            s.setString(6, run.actor());
            s.setString(7, run.environment());
            s.setString(8, run.namespace());
            s.setInt(9, run.ttlSeconds());
            s.setString(10, run.correlationId());
            s.setTimestamp(11, Timestamp.from(run.startedAt()));
            s.setTimestamp(12, Timestamp.from(run.expiresAt()));
            s.executeUpdate();
            return true;
        } catch (SQLException error) {
            if ("23505".equals(error.getSQLState())) {
                return false;
            }
            throw new StoreException(error);
        }
    }

    /** Change an open run's status and operator step. Ignored once the run has ended. */
    public void progress(String runId, RunState status, String operatorStep) {
        update("UPDATE scenario_run SET status = ?, operator_step = ? WHERE run_id = ? AND ended_at IS NULL",
                status.name(), operatorStep, runId);
    }

    /** End an open run. False when it had already ended (the first ending wins). */
    public boolean end(String runId, RunState status, String detail, Instant now) {
        return update("UPDATE scenario_run SET status = ?, detail = ?, operator_step = NULL, ended_at = ?"
                + " WHERE run_id = ? AND ended_at IS NULL", status.name(), detail, Timestamp.from(now), runId) == 1;
    }

    public void resetResult(String runId, String result, String detail, Instant now) {
        update("UPDATE scenario_run SET reset_result = ?, reset_detail = ?, reset_at = ? WHERE run_id = ?",
                result, detail, Timestamp.from(now), runId);
    }

    /** Merge one field into the run's impact record (fixture and order IDs). */
    public void impact(String runId, String field, Object value) {
        ObjectNode node = JSON.createObjectNode();
        node.set(field, JSON.valueToTree(value));
        update("UPDATE scenario_run SET impact = impact || ?::jsonb WHERE run_id = ?", node.toString(), runId);
    }

    public Optional<Run> find(String runId) {
        List<Run> runs = runs("SELECT " + RUN_COLUMNS + " FROM scenario_run WHERE run_id = ?", runId);
        return runs.stream().findFirst();
    }

    public Optional<Run> open() {
        return runs("SELECT " + RUN_COLUMNS + " FROM scenario_run WHERE ended_at IS NULL").stream().findFirst();
    }

    public List<Run> recent(int limit) {
        return runs("SELECT " + RUN_COLUMNS + " FROM scenario_run ORDER BY started_at DESC LIMIT ?", limit);
    }

    public void audit(String runId, String scenario, String action, String outcome, String actor, String environment,
            String namespace, String detail, Instant now) {
        String sql = "INSERT INTO scenario_audit (run_id, scenario, action, outcome, actor, environment, namespace, detail, occurred_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, runId);
            s.setString(2, scenario);
            s.setString(3, action);
            s.setString(4, outcome);
            s.setString(5, actor);
            s.setString(6, environment);
            s.setString(7, namespace);
            s.setString(8, detail);
            s.setTimestamp(9, Timestamp.from(now));
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    public List<Audit> auditFor(String runId) {
        return audits("SELECT seq, run_id, scenario, action, outcome, actor, environment, namespace, detail, occurred_at"
                + " FROM scenario_audit WHERE run_id = ? ORDER BY seq", runId);
    }

    /** Newest first, before an optional sequence number. */
    public List<Audit> auditPage(Long before, int limit) {
        String columns = "SELECT seq, run_id, scenario, action, outcome, actor, environment, namespace, detail, occurred_at"
                + " FROM scenario_audit";
        return before == null
                ? audits(columns + " ORDER BY seq DESC LIMIT ?", limit)
                : audits(columns + " WHERE seq < ? ORDER BY seq DESC LIMIT ?", before, limit);
    }

    /** Retention: delete audit rows, then ended runs, older than the cutoff. Returns the audit rows deleted. */
    public int purge(Instant cutoff) {
        int audits = update("DELETE FROM scenario_audit WHERE occurred_at < ?", Timestamp.from(cutoff));
        update("DELETE FROM scenario_run r WHERE ended_at < ? AND NOT EXISTS"
                + " (SELECT 1 FROM scenario_audit a WHERE a.run_id = r.run_id)", Timestamp.from(cutoff));
        return audits;
    }

    private int update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    private List<Run> runs(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            List<Run> runs = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    runs.add(new Run(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            RunState.valueOf(r.getString(5)), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                            r.getString(10), r.getInt(11), r.getString(12), JSON.readTree(r.getString(13)), instant(r, 14),
                            instant(r, 15), instant(r, 16), r.getString(17), r.getString(18), instant(r, 19)));
                }
            }
            return runs;
        } catch (SQLException error) {
            throw new StoreException(error);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Stored impact is not JSON", error);
        }
    }

    private List<Audit> audits(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            List<Audit> audits = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    audits.add(new Audit(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                            r.getString(6), r.getString(7), r.getString(8), r.getString(9), instant(r, 10)));
                }
            }
            return audits;
        } catch (SQLException error) {
            throw new StoreException(error);
        }
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof Long l) {
                s.setLong(i + 1, l);
            } else if (value instanceof Integer n) {
                s.setInt(i + 1, n);
            } else if (value instanceof Timestamp t) {
                s.setTimestamp(i + 1, t);
            } else if (value == null) {
                s.setObject(i + 1, null);
            } else {
                s.setString(i + 1, value.toString());
            }
        }
    }

    private static Instant instant(ResultSet r, int column) throws SQLException {
        Timestamp t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
