package org.box1.scenario.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.box1.scenario.Stardate;
import org.box1.scenario.catalog.Scenario;
import org.box1.scenario.run.RunStore;

/** Response bodies. The stardate is display-only; the ISO 8601 timestamp beside it is authoritative. */
public final class Views {
    private Views() {}

    public record Start(Integer ttlSeconds) {}

    public record ScenarioView(String name, String title, String description, String mechanism, String availability,
            String notPossibleReason, List<String> prerequisites, List<String> fixtures, int defaultDurationSeconds,
            int maxDurationSeconds, String expectedObservation, String resetAction, List<String> operatorSteps) {
        static ScenarioView of(Scenario s) {
            return new ScenarioView(s.name(), s.title(), s.description(), s.mechanism().name(), s.availability().name(),
                    s.notPossibleReason(), s.prerequisites(), s.fixtures(), s.defaultDurationSeconds(), s.maxDurationSeconds(),
                    s.expectedObservation(), s.resetAction(), s.operatorSteps());
        }
    }

    public record AuditView(long seq, String scenarioRunId, String scenario, String action, String outcome, String actor,
            String environment, String namespace, String detail, Instant occurredAt, String stardate) {
        static AuditView of(RunStore.Audit a) {
            return new AuditView(a.seq(), a.runId(), a.scenario(), a.action(), a.outcome(), a.actor(), a.environment(),
                    a.namespace(), a.detail(), a.occurredAt(), Stardate.of(a.occurredAt()));
        }
    }

    public record RunView(String scenarioRunId, String scenario, String title, String mode, String status, String detail,
            String operatorStep, String operatorStepId, String actor, String environment, String namespace, int ttlSeconds, String correlationId,
            JsonNode impact, Instant startedAt, String startedStardate, Instant expiresAt, Instant endedAt, String resetResult,
            String resetDetail, Instant resetAt, List<AuditView> audit) {
        static RunView of(RunStore.Run r, List<RunStore.Audit> audit) {
            return new RunView(r.runId(), r.scenario(), r.title(), r.mode(), r.status().name(), r.detail(), r.operatorStep(),
                    r.operatorStepId(), r.actor(), r.environment(), r.namespace(), r.ttlSeconds(), r.correlationId(), r.impact(), r.startedAt(),
                    Stardate.of(r.startedAt()), r.expiresAt(), r.endedAt(), r.resetResult(), r.resetDetail(), r.resetAt(),
                    audit == null ? null : audit.stream().map(AuditView::of).toList());
        }
    }
}
