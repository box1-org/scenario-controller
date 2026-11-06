package org.box1.scenario.run;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
import org.box1.scenario.fixtures.Fixtures;
import org.box1.scenario.upstream.AdapterApi;
import org.box1.scenario.upstream.InventoryApi;
import org.box1.scenario.upstream.InventoryToken;
import org.box1.scenario.upstream.OrderApi;
import org.box1.scenario.upstream.UpstreamException;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Starts, stops, expires and resets scenario runs. One run at a time, in one replica (the Deployment uses Recreate).
 * Every refusal and every state change is audited. A run never outlives its TTL: the run's own waits end at the TTL,
 * and the expiry sweep ends any run whose thread did not. At startup, runs left open by a previous process are
 * aborted and reset, and resets that a dead process never recorded are completed.
 */
@ApplicationScoped
public class Engine implements RunContext.Recorder {
    private static final Logger LOG = Logger.getLogger(Engine.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The actor recorded on the controller's own audit rows. Token subjects are UUIDs, so they cannot collide. */
    public static final String SYSTEM = "system:scenario-controller";
    /** How long past its TTL a run's own thread has to finish before the sweep ends it. */
    static final Duration GRACE = Duration.ofSeconds(30);
    /** How long a reset waits for the run's orders to be decided before restoring stock. */
    static final Duration SETTLE = Duration.ofSeconds(30);

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
    private final Tracer tracer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "scenario-run"));
    /** Serialises starts, resets without a run, and endings within this process. */
    private final Object lock = new Object();
    private final AtomicReference<Handle> active = new AtomicReference<>();
    private volatile boolean recoveryPending = true;

    /** The running run in this process: its stop flag and its own Future, so a stop never touches another run. */
    record Handle(String runId, AtomicBoolean stop, AtomicReference<Future<?>> future) {}

    public Engine(Catalog catalog, Gate gate, RunStore store, FixtureService fixtures, Clock clock, ScenarioSettings settings,
            OrderApi orders, InventoryApi inventory, AdapterApi adapter, Instance<ScenarioExecutor> executors,
            MeterRegistry metrics, Tracer tracer) {
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
        this.tracer = tracer;
        for (Scenario s : catalog.all()) {
            if (s.available() && !this.executors.containsKey(s.name())) {
                throw new IllegalStateException("Available scenario " + s.name() + " has no implementation");
            }
        }
        metrics.gauge("scenario_active_runs", this, e -> e.active.get() == null ? 0 : 1);
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
        synchronized (lock) {
            Instant now = clock.instant();
            String runId = "scn_" + HexFormat.of().formatHex(bytes(10));
            RunStore.Run run = new RunStore.Run(runId, scenario.name(), scenario.title(), scenario.mechanism().name(),
                    RunState.RUNNING, null, null, null, actor, gate.environment(), gate.namespace(), ttl, "scenario-" + runId,
                    null, now, now.plusSeconds(ttl), null, null, null, null);
            String detail = "ttlSeconds=" + ttl + "; mode=" + scenario.mechanism() + "; targets=" + String.join(",", gate.targets());
            if (!store.start(run, detail, scenario.fixtures())) {
                String other = store.open().map(RunStore.Run::runId).orElse("unknown");
                deny(scenario.name(), "START", actor, "Another scenario run is active: " + other);
                throw new RequestProblem(409, "Conflict", "Another scenario run is active: " + other);
            }
            Handle handle = new Handle(runId, new AtomicBoolean(), new AtomicReference<>());
            active.set(handle);
            RunStore.Run stored = store.find(runId).orElseThrow();
            handle.future().set(worker.submit(() -> execute(scenario, stored, handle)));
            LOG.infov("Scenario started: scenario={0}, scenarioRunId={1}", scenario.name(), runId);
            return stored;
        }
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
     * fixture stock is restored and the reset is audited on its own; this is refused while any other run is open, so a
     * reset never adds stock under a running scenario.
     */
    public Map<String, Object> reset(String name, String actor) {
        Scenario scenario = known(name, "RESET", actor);
        allowed(scenario.name(), "RESET", actor);
        Optional<RunStore.Run> open = store.open();
        if (open.isPresent() && open.get().scenario().equals(scenario.name())) {
            store.audit(open.get().runId(), scenario.name(), "RESET", "ACCEPTED", actor, gate.environment(), gate.namespace(),
                    "stops the active run first", clock.instant());
            halt(open.get(), RunState.STOPPED, "Stopped by reset from " + actor);
            RunStore.Run run = store.find(open.get().runId()).orElseThrow();
            return Map.of("scenarioRunId", run.runId(), "resetResult", String.valueOf(run.resetResult()),
                    "resetDetail", String.valueOf(run.resetDetail()));
        }
        synchronized (lock) {
            Optional<RunStore.Run> other = store.open();
            if (other.isPresent()) {
                deny(scenario.name(), "RESET", actor, "Another scenario run is active: " + other.get().runId());
                throw new RequestProblem(409, "Conflict", "Another scenario run is active: " + other.get().runId());
            }
            String resetId = "rst_" + HexFormat.of().formatHex(bytes(10));
            Outcome outcome = restore(scenario, resetId, List.of(), null);
            store.audit(null, scenario.name(), "RESET", outcome.result(), actor, gate.environment(), gate.namespace(),
                    resetId + ": " + outcome.detail(), clock.instant());
            metrics.counter("scenario_resets_total", "result", outcome.result()).increment();
            return Map.of("resetId", resetId, "resetResult", outcome.result(), "resetDetail", outcome.detail());
        }
    }

    /** Seed the fixture set through inventory's API. Idempotent. */
    public FixtureService.SeedResult seed(String actor) {
        allowed("fixtures", "SEED", actor);
        FixtureService.SeedResult result;
        try {
            result = fixtures.seed("scenario-seed-" + HexFormat.of().formatHex(bytes(6)));
        } catch (RuntimeException error) {
            String why = plain(error);
            store.audit(null, null, "SEED", "FAILED", actor, gate.environment(), gate.namespace(), why, clock.instant());
            throw new RequestProblem(502, "Bad Gateway", "Seeding failed: " + why);
        }
        store.audit(null, null, "SEED", "OK", actor, gate.environment(), gate.namespace(),
                "created=" + result.created() + "; unchanged=" + result.unchanged(), clock.instant());
        return result;
    }

    /**
     * Record an operator's confirmation of a step the controller cannot observe. Only the step the run is waiting
     * for, and only for this process's open run.
     */
    public RunStore.Run confirm(String runId, String step, String actor) {
        RunStore.Run run = store.find(runId).orElseThrow(() -> new RequestProblem(404, "Not Found", "No such run"));
        allowed(run.scenario(), "CONFIRM", actor);
        if (!isActive(runId) || run.endedAt() != null) {
            throw new RequestProblem(409, "Conflict", "Run " + runId + " is not active");
        }
        if (!step.equals(run.operatorStepId())) {
            throw new RequestProblem(409, "Conflict", "Run " + runId + " is not waiting for step " + step);
        }
        store.audit(runId, run.scenario(), "CONFIRM", step, actor, gate.environment(), gate.namespace(),
                "Operator confirmed step " + step, clock.instant());
        return store.find(runId).orElseThrow();
    }

    /** End runs whose TTL and grace have passed and whose thread did not end them; finish unrecorded resets. */
    @Scheduled(every = "${scenario.expiry-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        if (recoveryPending) {
            recover();
            return;
        }
        try {
            store.open().filter(r -> clock.instant().isAfter(r.expiresAt().plus(GRACE)))
                    .ifPresent(r -> halt(r, RunState.EXPIRED, "TTL of " + r.ttlSeconds() + " s passed"));
            completePendingResets();
        } catch (RuntimeException error) {
            LOG.warnv("Expiry sweep failed: {0}", error.getClass().getSimpleName());
        }
    }

    void onStart(@Observes StartupEvent event) {
        recover();
    }

    /**
     * A previous process may have died mid-run or between ending a run and resetting it. Nothing resumes silently: an
     * open run that is not this process's is aborted and reset, and missing resets are completed. Retried by the sweep
     * if anything fails here.
     */
    void recover() {
        try {
            store.open().filter(r -> !isActive(r.runId())).ifPresent(r -> {
                LOG.warnv("Aborting run left open by a previous process: scenarioRunId={0}", r.runId());
                halt(r, RunState.ABORTED_RESTART, "Controller restarted during the run");
            });
            completePendingResets();
            recoveryPending = false;
        } catch (RuntimeException error) {
            LOG.warnv("Recovery of open runs deferred: {0}", error.getClass().getSimpleName());
            recoveryPending = true;
        }
    }

    /** Daily retention (D-008): 90 days by database time, the rule the audit trigger enforces. */
    @Scheduled(cron = "0 17 3 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void retention() {
        LOG.infov("Audit retention removed {0} rows", store.purge());
    }

    void onStop(@Observes ShutdownEvent event) {
        Handle handle = active.get();
        if (handle != null) {
            handle.stop().set(true);
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }

    private boolean isActive(String runId) {
        Handle handle = active.get();
        return handle != null && handle.runId().equals(runId);
    }

    private void completePendingResets() {
        for (RunStore.Run run : store.pendingResets()) {
            if (isActive(run.runId())) {
                continue;
            }
            Optional<Scenario> scenario = catalog.find(run.scenario());
            if (scenario.isEmpty()) {
                record(run, "RESET_FAILED", "Scenario " + run.scenario() + " is no longer in the catalog; reset by hand");
            } else {
                record(run, restore(scenario.get(), run.runId(), orderIds(run), null));
            }
        }
    }

    private void execute(Scenario scenario, RunStore.Run run, Handle handle) {
        MDC.put("scenarioRunId", run.runId());
        MDC.put("correlationId", run.correlationId());
        Span span = tracer.spanBuilder("scenario " + scenario.name()).setAttribute("scenario.run_id", run.runId()).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            ScenarioExecutor.Result result;
            try {
                RunContext context = new RunContext(run, this, clock, settings.pollInterval(), settings.shipmentSettle(), handle.stop(), orders, inventory,
                        adapter, fixtures);
                result = executors.get(scenario.name()).execute(context);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                result = null;
            } catch (RuntimeException error) {
                // A stop interrupts calls outside the bounded waits too; that is a stop, not a failure.
                if (handle.stop().get() || error instanceof RunContext.Stopped) {
                    result = null;
                } else if (error instanceof RunContext.Failure) {
                    result = ScenarioExecutor.Result.failed(error.getMessage());
                } else {
                    result = ScenarioExecutor.Result.failed(plain(error));
                }
            }
            if (result != null) {
                finish(scenario, run, result.state(), result.detail(), result.guidedReset());
            }
        } catch (RuntimeException error) {
            LOG.errorv("Ending run {0} failed; the sweep completes it: {1}", run.runId(), error.getClass().getSimpleName());
        } finally {
            span.end();
            active.compareAndSet(handle, null);
            MDC.remove("scenarioRunId");
            MDC.remove("correlationId");
        }
    }

    /** Stop the run's own thread, if it is in this process, and end it with the given state. */
    private void halt(RunStore.Run run, RunState state, String detail) {
        Handle handle = active.get();
        if (handle != null && handle.runId().equals(run.runId())) {
            handle.stop().set(true);
            Future<?> future = handle.future().get();
            if (future != null) {
                future.cancel(true);
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception expected) {
                    // Cancelled, finished or still stopping; the first ending wins either way.
                }
            }
            active.compareAndSet(handle, null);
        }
        Optional<Scenario> scenario = catalog.find(run.scenario());
        if (scenario.isEmpty()) {
            if (store.end(run, state, detail + "; scenario no longer in the catalog", SYSTEM, clock.instant())) {
                record(run, "RESET_FAILED", "Scenario " + run.scenario() + " is no longer in the catalog; reset by hand");
            }
            return;
        }
        String step = scenario.get().mechanism() == Scenario.Mechanism.GUIDED_ERP
                ? "Undo any console step taken for this run. " + scenario.get().resetAction() : null;
        finish(scenario.get(), run, state, detail, step);
    }

    /** End the run and audit the result atomically (first ending wins), then reset its fixture state. */
    private void finish(Scenario scenario, RunStore.Run run, RunState state, String detail, String guidedReset) {
        synchronized (lock) {
            if (!store.end(run, state, detail, SYSTEM, clock.instant())) {
                return;
            }
        }
        metrics.counter("scenario_runs_total", "scenario", scenario.name(), "outcome", state.name()).increment();
        RunStore.Run ended = store.find(run.runId()).orElse(run);
        record(ended, restore(scenario, run.runId(), orderIds(ended), guidedReset));
        LOG.infov("Scenario ended: scenario={0}, scenarioRunId={1}, status={2}", scenario.name(), run.runId(), state);
    }

    record Outcome(String result, String detail) {}

    private void record(RunStore.Run run, Outcome outcome) {
        record(run, outcome.result(), outcome.detail());
    }

    private void record(RunStore.Run run, String result, String detail) {
        store.resetResult(run.runId(), result, detail, clock.instant());
        store.audit(run.runId(), run.scenario(), "RESET", result, SYSTEM, gate.environment(), gate.namespace(), detail,
                clock.instant());
        metrics.counter("scenario_resets_total", "result", result).increment();
    }

    /**
     * Restore the scenario's fixture stock. First waits (bounded) until the run's own orders are decided, so restored
     * stock can never be taken by an order of the run that is still in flight.
     */
    private Outcome restore(Scenario scenario, String resetId, List<String> runOrders, String guidedReset) {
        List<Fixtures.StockPlan> records = FixtureService.recordsFor(scenario.fixtures());
        String stock;
        try {
            List<String> undecided = undecided(runOrders);
            if (!undecided.isEmpty()) {
                return new Outcome("RESET_FAILED", "Orders still undecided after " + SETTLE.toSeconds() + " s: " + undecided
                        + "; reset again later");
            }
            stock = records.isEmpty() ? "no fixture stock" : "restored " + fixtures.restore(records, resetId, "scenario-" + resetId);
        } catch (RuntimeException error) {
            return new Outcome("RESET_FAILED", "Stock restore failed: " + plain(error));
        }
        if (guidedReset != null) {
            return new Outcome("RESET_GUIDED", stock + ". Operator step: " + guidedReset);
        }
        return new Outcome("RESET_OK", stock);
    }

    private List<String> undecided(List<String> orderIds) {
        Instant end = clock.instant().plus(SETTLE);
        List<String> pending = orderIds;
        while (true) {
            pending = pending.stream().filter(id -> {
                try {
                    return "SUBMITTED".equals(orders.get(id, null).state());
                } catch (UpstreamException error) {
                    return true;
                }
            }).toList();
            if (pending.isEmpty() || !clock.instant().isBefore(end)) {
                return pending;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return pending;
            }
        }
    }

    private static List<String> orderIds(RunStore.Run run) {
        JsonNode node = run.impact() == null ? null : run.impact().get("orders");
        List<String> ids = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> ids.add(n.asText()));
        }
        return ids;
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
        if (run.endedAt() != null) {
            return;
        }
        store.audit(runId, run.scenario(), action, outcome, SYSTEM, gate.environment(), gate.namespace(), detail, clock.instant());
    }

    @Override
    public void progress(String runId, RunState state, String operatorStep, String operatorStepId) {
        store.progress(runId, state, operatorStep, operatorStepId);
    }

    @Override
    public void impact(String runId, String field, Object value) {
        store.impact(runId, field, value);
    }

    @Override
    public boolean confirmed(String runId, String step) {
        return store.auditFor(runId).stream().anyMatch(a -> "CONFIRM".equals(a.action()) && step.equals(a.outcome()));
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /**
     * Text for callers and the audit. Upstream and run messages are written plain by this service; anything else is
     * replaced, so hostnames, SQL states and JVM messages stay in the logs.
     */
    static String plain(Throwable error) {
        if (error instanceof UpstreamException || error instanceof RunContext.Failure
                || error instanceof InventoryToken.NotConfigured) {
            return error.getMessage();
        }
        LOG.warnv(error, "Unexpected error");
        return "Internal error (" + error.getClass().getSimpleName() + "); see the controller log";
    }
}
