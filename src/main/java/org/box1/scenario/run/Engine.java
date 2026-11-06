package org.box1.scenario.run;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.box1.scenario.Gate;
import org.box1.scenario.ScenarioSettings;
import org.box1.scenario.api.RequestProblem;
import org.box1.scenario.catalog.Catalog;
import org.box1.scenario.catalog.Scenario;
import org.box1.scenario.fixtures.FixtureService;
import org.box1.scenario.upstream.AdapterApi;
import org.box1.scenario.upstream.InventoryApi;
import org.box1.scenario.upstream.InventoryToken;
import org.box1.scenario.upstream.OrderApi;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Starts, stops, expires and resets scenario runs. One run at a time. Every refusal and every state change is
 * audited. A run never outlives its TTL: the run's own waits end at the TTL, and the expiry sweep ends any run whose
 * thread is gone. At startup, runs left open by a previous process are aborted and reset.
 */
@ApplicationScoped
public class Engine implements RunContext.Recorder {
    private static final Logger LOG = Logger.getLogger(Engine.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** How long past its TTL a run's own thread has to finish before the sweep ends it. */
    static final Duration GRACE = Duration.ofSeconds(30);

    private final Catalog catalog;
    private final Gate gate;
    private final RunStore store;
    private final FixtureService fixtures;
    private final Clock clock;
    private final ScenarioSettings settings;
    private final OrderApi orders;
    private final InventoryApi inventory;
    private final AdapterApi adapter;
    private final Map<String, ScenarioExecutor> executors;
    private final MeterRegistry metrics;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "scenario-run"));
    private volatile Handle active;
    private volatile boolean recoveryPending = true;

    record Handle(String runId, AtomicBoolean stop, Thread[] thread) {}

    public Engine(Catalog catalog, Gate gate, RunStore store, FixtureService fixtures, Clock clock, ScenarioSettings settings,
            OrderApi orders, InventoryApi inventory, AdapterApi adapter, Instance<ScenarioExecutor> executors,
            MeterRegistry metrics) {
        this.catalog = catalog;
        this.gate = gate;
        this.store = store;
        this.fixtures = fixtures;
        this.clock = clock;
        this.settings = settings;
        this.orders = orders;
        this.inventory = inventory;
        this.adapter = adapter;
        this.executors = executors.stream().collect(Collectors.toMap(ScenarioExecutor::name, Function.identity()));
        this.metrics = metrics;
        for (Scenario s : catalog.all()) {
            if (s.available() && !this.executors.containsKey(s.name())) {
                throw new IllegalStateException("Available scenario " + s.name() + " has no implementation");
            }
        }
        metrics.gauge("scenario_active_runs", this, e -> e.active == null ? 0 : 1);
    }

    /** Start a scenario. Refusals are audited and returned as plain Problems. */
    public RunStore.Run start(String name, Integer ttlSeconds, String actor) {
        Scenario scenario = known(name, "START", actor);
        allowed(scenario.name(), "START", actor);
        if (!scenario.available()) {
            deny(scenario.name(), "START", actor, "Scenario " + name + " is not yet possible: " + scenario.notPossibleReason());
            throw new RequestProblem(409, "Conflict", "Scenario " + name + " is not yet possible: " + scenario.notPossibleReason());
        }
        int ttl = scenario.defaultDurationSeconds();
        if (ttlSeconds != null) {
            if (ttlSeconds < 1 || ttlSeconds > scenario.maxDurationSeconds()) {
                deny(scenario.name(), "START", actor, "ttlSeconds out of range");
                throw new RequestProblem(400, "Bad Request", "ttlSeconds must be 1 to " + scenario.maxDurationSeconds());
            }
            ttl = ttlSeconds;
        }
        Instant now = clock.instant();
        String runId = "scn_" + HexFormat.of().formatHex(bytes(10));
        RunStore.Run run = new RunStore.Run(runId, scenario.name(), scenario.title(), scenario.mechanism().name(), RunState.RUNNING,
                null, null, actor, gate.environment(), gate.namespace(), ttl, "scenario-" + runId, null, now,
                now.plusSeconds(ttl), null, null, null, null);
        if (!store.insert(run)) {
            String other = store.open().map(RunStore.Run::runId).orElse("unknown");
            deny(scenario.name(), "START", actor, "Another scenario run is active: " + other);
            throw new RequestProblem(409, "Conflict", "Another scenario run is active: " + other);
        }
        store.audit(runId, scenario.name(), "START", "ACCEPTED", actor, gate.environment(), gate.namespace(),
                "ttlSeconds=" + ttl + "; mode=" + scenario.mechanism(), now);
        store.impact(runId, "fixtures", scenario.fixtures());
        AtomicBoolean stop = new AtomicBoolean();
        Thread[] thread = new Thread[1];
        Handle handle = new Handle(runId, stop, thread);
        active = handle;
        RunStore.Run stored = store.find(runId).orElseThrow();
        worker.submit(() -> {
            thread[0] = Thread.currentThread();
            execute(scenario, stored, handle);
        });
        LOG.infov("Scenario started: scenario={0}, scenarioRunId={1}", scenario.name(), runId);
        return stored;
    }

    /** Stop the active run of a scenario, then reset. */
    public RunStore.Run stop(String name, String actor) {
        Scenario scenario = known(name, "STOP", actor);
        allowed(scenario.name(), "STOP", actor);
        RunStore.Run run = store.open().filter(r -> r.scenario().equals(scenario.name())).orElseThrow(() -> {
            deny(scenario.name(), "STOP", actor, "No active run of scenario " + name);
            return new RequestProblem(409, "Conflict", "No active run of scenario " + name);
        });
        store.audit(run.runId(), run.scenario(), "STOP", "ACCEPTED", actor, gate.environment(), gate.namespace(), null, clock.instant());
        halt(run, RunState.STOPPED, "Stopped by " + actor);
        return store.find(run.runId()).orElseThrow();
    }

    /**
     * Reset a scenario's fixture state. With an active run of that scenario, the run is stopped first. Without one,
     * fixture stock is restored and the reset is audited on its own.
     */
    public Map<String, Object> reset(String name, String actor) {
        Scenario scenario = known(name, "RESET", actor);
        allowed(scenario.name(), "RESET", actor);
        Optional<RunStore.Run> open = store.open().filter(r -> r.scenario().equals(scenario.name()));
        if (open.isPresent()) {
            store.audit(open.get().runId(), scenario.name(), "RESET", "ACCEPTED", actor, gate.environment(), gate.namespace(),
                    "stops the active run first", clock.instant());
            halt(open.get(), RunState.STOPPED, "Stopped by reset from " + actor);
            RunStore.Run run = store.find(open.get().runId()).orElseThrow();
            return Map.of("scenarioRunId", run.runId(), "resetResult", String.valueOf(run.resetResult()),
                    "resetDetail", String.valueOf(run.resetDetail()));
        }
        String resetId = "rst_" + HexFormat.of().formatHex(bytes(10));
        Outcome outcome = restore(scenario, resetId, null);
        store.audit(null, scenario.name(), "RESET", outcome.result(), actor, gate.environment(), gate.namespace(),
                resetId + ": " + outcome.detail(), clock.instant());
        return Map.of("resetId", resetId, "resetResult", outcome.result(), "resetDetail", outcome.detail());
    }

    /** Seed the fixture set through inventory's API. Idempotent. */
    public FixtureService.SeedResult seed(String actor) {
        allowed("fixtures", "SEED", actor);
        try {
            FixtureService.SeedResult result = fixtures.seed("scenario-seed-" + HexFormat.of().formatHex(bytes(6)));
            store.audit(null, null, "SEED", "OK", actor, gate.environment(), gate.namespace(),
                    "created=" + result.created() + "; unchanged=" + result.unchanged(), clock.instant());
            return result;
        } catch (RuntimeException error) {
            store.audit(null, null, "SEED", "FAILED", actor, gate.environment(), gate.namespace(), plain(error), clock.instant());
            throw new RequestProblem(502, "Bad Gateway", "Seeding failed: " + plain(error));
        }
    }

    /** End runs whose TTL and grace have passed and whose thread did not end them. */
    @Scheduled(every = "${scenario.expiry-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        if (recoveryPending) {
            recover();
        }
        store.open().filter(r -> clock.instant().isAfter(r.expiresAt().plus(GRACE)))
                .ifPresent(r -> halt(r, RunState.EXPIRED, "TTL of " + r.ttlSeconds() + " s passed"));
    }

    /** A previous process may have died mid-run. Nothing resumes silently: abort and reset. */
    void onStart(@Observes StartupEvent event) {
        recover();
    }

    /** Abort a run a previous process left open. Retried by the sweep if the store is unavailable at startup. */
    void recover() {
        try {
            store.open().filter(r -> active == null || !active.runId().equals(r.runId())).ifPresent(r -> {
                LOG.warnv("Aborting run left open by a previous process: scenarioRunId={0}", r.runId());
                halt(r, RunState.ABORTED_RESTART, "Controller restarted during the run");
            });
            recoveryPending = false;
        } catch (RunStore.StoreException error) {
            LOG.warn("Recovery of open runs deferred: audit store unavailable");
            recoveryPending = true;
        }
    }

    /** Daily retention (D-008). */
    @Scheduled(cron = "0 17 3 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void retention() {
        int deleted = store.purge(clock.instant().minus(settings.retention()));
        LOG.infov("Audit retention removed {0} rows", deleted);
    }

    void onStop(@Observes ShutdownEvent event) {
        Handle handle = active;
        if (handle != null) {
            handle.stop().set(true);
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }

    private void execute(Scenario scenario, RunStore.Run run, Handle handle) {
        MDC.put("scenarioRunId", run.runId());
        MDC.put("correlationId", run.correlationId());
        ScenarioExecutor.Result result;
        try {
            RunContext context = new RunContext(run, this, clock, settings.pollInterval(), handle.stop(), orders, inventory,
                    adapter, fixtures);
            result = executors.get(scenario.name()).execute(context);
        } catch (RunContext.Stopped | InterruptedException error) {
            result = null;
        } catch (RunContext.Failure error) {
            result = ScenarioExecutor.Result.failed(error.getMessage());
        } catch (RuntimeException error) {
            LOG.warnv("Scenario failed: {0}", plain(error));
            result = ScenarioExecutor.Result.failed(plain(error));
        } finally {
            MDC.remove("scenarioRunId");
            MDC.remove("correlationId");
        }
        if (result != null) {
            finish(scenario, run.runId(), result.state(), result.detail(), result.guidedReset());
        }
        if (active == handle) {
            active = null;
        }
    }

    /** Stop the run's thread, if it is ours, and end it with the given state. */
    private void halt(RunStore.Run run, RunState state, String detail) {
        Handle handle = active;
        Scenario scenario = catalog.find(run.scenario()).orElseThrow();
        if (handle != null && handle.runId().equals(run.runId())) {
            handle.stop().set(true);
            Thread thread = handle.thread()[0];
            if (thread != null) {
                thread.interrupt();
            }
            awaitIdle();
            active = null;
        }
        String step = scenario.mechanism() == Scenario.Mechanism.GUIDED_ERP && !scenario.operatorSteps().isEmpty()
                ? "Undo any console step taken for this run. " + scenario.resetAction() : null;
        finish(scenario, run.runId(), state, detail, step);
    }

    private void awaitIdle() {
        try {
            worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            LOG.warn("Run thread did not stop within 10 s");
        }
    }

    /** End the run (first ending wins), then reset its fixture state and record the result. */
    private synchronized void finish(Scenario scenario, String runId, RunState state, String detail, String guidedReset) {
        Instant now = clock.instant();
        if (!store.end(runId, state, detail, now)) {
            return;
        }
        store.audit(runId, scenario.name(), "RESULT", state.name(), "scenario-controller", gate.environment(), gate.namespace(),
                detail, now);
        metrics.counter("scenario_runs_total", "scenario", scenario.name(), "outcome", state.name()).increment();
        Outcome outcome = restore(scenario, runId, guidedReset);
        store.resetResult(runId, outcome.result(), outcome.detail(), clock.instant());
        store.audit(runId, scenario.name(), "RESET", outcome.result(), "scenario-controller", gate.environment(), gate.namespace(),
                outcome.detail(), clock.instant());
        LOG.infov("Scenario ended: scenario={0}, scenarioRunId={1}, status={2}, reset={3}", scenario.name(), runId, state,
                outcome.result());
    }

    record Outcome(String result, String detail) {}

    private Outcome restore(Scenario scenario, String resetId, String guidedReset) {
        List<org.box1.scenario.fixtures.Fixtures.StockPlan> records = FixtureService.recordsFor(scenario.fixtures());
        String stock;
        try {
            stock = records.isEmpty() ? "no fixture stock" : "restored " + fixtures.restore(records, resetId, "scenario-" + resetId);
        } catch (InventoryToken.NotConfigured error) {
            return new Outcome("RESET_FAILED", error.getMessage());
        } catch (RuntimeException error) {
            return new Outcome("RESET_FAILED", "Stock restore failed: " + plain(error));
        }
        if (guidedReset != null) {
            return new Outcome("RESET_GUIDED", stock + ". Operator step: " + guidedReset);
        }
        return new Outcome("RESET_OK", stock);
    }

    private Scenario known(String name, String action, String actor) {
        return catalog.find(name).orElseThrow(() -> {
            deny(null, action, actor, "Unknown scenario");
            return new RequestProblem(404, "Not Found", "Scenario is not in the catalog");
        });
    }

    private void allowed(String scenario, String action, String actor) {
        String refusal = gate.refusal(scenario);
        if (refusal != null) {
            deny(scenario, action, actor, refusal);
            throw new RequestProblem(403, "Forbidden", refusal);
        }
    }

    private void deny(String scenario, String action, String actor, String detail) {
        store.audit(null, scenario, action, "DENIED", actor, gate.environment(), gate.namespace(), detail, clock.instant());
        metrics.counter("scenario_refusals_total", "action", action).increment();
    }

    @Override
    public void note(String runId, String action, String outcome, String detail) {
        RunStore.Run run = store.find(runId).orElseThrow();
        store.audit(runId, run.scenario(), action, outcome, "scenario-controller", gate.environment(), gate.namespace(), detail,
                clock.instant());
    }

    @Override
    public void progress(String runId, RunState state, String operatorStep) {
        store.progress(runId, state, operatorStep);
    }

    @Override
    public void impact(String runId, String field, Object value) {
        store.impact(runId, field, value);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** Exception text without stack or body; upstream messages are already plain. */
    static String plain(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
