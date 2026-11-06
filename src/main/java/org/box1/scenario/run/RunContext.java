package org.box1.scenario.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.box1.scenario.fixtures.FixtureService;
import org.box1.scenario.upstream.AdapterApi;
import org.box1.scenario.upstream.InventoryApi;
import org.box1.scenario.upstream.OrderApi;
import org.box1.scenario.upstream.UpstreamException;
import org.jboss.logging.Logger;

/** What a running scenario may do: typed service calls, audit notes, operator steps and bounded waits. */
public final class RunContext {
    private static final Logger LOG = Logger.getLogger(RunContext.class);

    private final RunStore.Run run;
    private final Recorder recorder;
    private final Clock clock;
    private final Duration poll;
    private final AtomicBoolean stop;
    public final OrderApi orders;
    public final InventoryApi inventory;
    public final AdapterApi adapter;
    public final FixtureService fixtures;

    /** Writes the audit trail and run progress. */
    public interface Recorder {
        void note(String runId, String action, String outcome, String detail);

        void progress(String runId, RunState state, String operatorStep, String operatorStepId);

        void impact(String runId, String field, Object value);

        boolean confirmed(String runId, String step);
    }

    /** The run was stopped while waiting. */
    public static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("Stopped");
        }
    }

    private final Duration settle;

    public RunContext(RunStore.Run run, Recorder recorder, Clock clock, Duration poll, Duration settle, AtomicBoolean stop,
            OrderApi orders, InventoryApi inventory, AdapterApi adapter, FixtureService fixtures) {
        this.settle = settle;
        this.run = run;
        this.recorder = recorder;
        this.clock = clock;
        this.poll = poll;
        this.stop = stop;
        this.orders = orders;
        this.inventory = inventory;
        this.adapter = adapter;
        this.fixtures = fixtures;
    }

    /** How long to watch after a first shipment for a duplicate. */
    public Duration settle() {
        return settle;
    }

    public String runId() {
        return run.runId();
    }

    public String correlationId() {
        return run.correlationId();
    }

    /** Run-scoped order idempotency key, so a repeat replays the same order. */
    public String key(int n) {
        return "stardock-" + run.runId() + "-" + n;
    }

    public Instant now() {
        return clock.instant();
    }

    public void note(String action, String detail) {
        recorder.note(run.runId(), action, "OK", detail);
    }

    public void impact(String field, Object value) {
        recorder.impact(run.runId(), field, value);
    }

    /** Show the operator's console step; the run then waits for its observable effect. */
    public void awaitOperator(String step) {
        recorder.progress(run.runId(), RunState.AWAITING_OPERATOR, step, null);
        recorder.note(run.runId(), "OPERATOR_STEP", "WAITING", step);
    }

    /**
     * Show a console step whose effect the controller cannot observe, and wait (until the TTL) for the operator to
     * confirm it through {@code POST /internal/v1/runs/{scenarioRunId}/confirm} with this step ID. The confirmation is
     * audited with the operator's identity; it is an attestation, recorded as such.
     */
    public boolean awaitConfirmation(String stepId, String step) throws InterruptedException {
        String text = step + " Then confirm step \"" + stepId + "\" (scripts/scenario.sh confirm " + run.runId() + " " + stepId + ").";
        recorder.progress(run.runId(), RunState.AWAITING_OPERATOR, text, stepId);
        recorder.note(run.runId(), "OPERATOR_STEP", "WAITING", text);
        boolean done = await("confirmation of " + stepId,
                () -> recorder.confirmed(run.runId(), stepId) ? Optional.of(true) : Optional.<Boolean>empty()).isPresent();
        resume();
        return done;
    }

    public void resume() {
        recorder.progress(run.runId(), RunState.RUNNING, null, null);
    }

    /**
     * A one-shot read with up to three attempts, one second apart; transient upstream failures should not fail a
     * run that has already reached its outcome.
     */
    public <T> T read(Supplier<T> call) throws InterruptedException {
        for (int attempt = 1;; attempt++) {
            checkStop();
            try {
                return call.get();
            } catch (UpstreamException error) {
                refusedFast(error);
                if (attempt >= 3) {
                    throw error;
                }
                Thread.sleep(1000);
            }
        }
    }

    /** A service that refuses the controller's call will keep refusing; fail now rather than at the TTL. */
    private static void refusedFast(UpstreamException error) {
        if (error.status() == 401 || error.status() == 403) {
            throw new Failure(error.getMessage() + "; the controller is not allowed to call it");
        }
    }

    public void checkStop() {
        if (stop.get() || Thread.currentThread().isInterrupted()) {
            throw new Stopped();
        }
    }

    /**
     * Poll until the probe returns a value, the run's TTL passes (empty) or the run is stopped (Stopped). Upstream
     * failures while polling are tolerated and the last one is logged; they never count as an observation.
     */
    public <T> Optional<T> await(String what, Supplier<Optional<T>> probe) throws InterruptedException {
        return await(what, run.expiresAt(), probe);
    }

    /** As {@link #await(String, Supplier)}, but ending at the earlier of the TTL and {@code limit} from now. */
    public <T> Optional<T> await(String what, Duration limit, Supplier<Optional<T>> probe) throws InterruptedException {
        Instant cap = clock.instant().plus(limit);
        return await(what, cap.isBefore(run.expiresAt()) ? cap : run.expiresAt(), probe);
    }

    private <T> Optional<T> await(String what, Instant deadline, Supplier<Optional<T>> probe) throws InterruptedException {
        String lastError = null;
        while (true) {
            checkStop();
            try {
                Optional<T> value = probe.get();
                if (value.isPresent()) {
                    return value;
                }
            } catch (UpstreamException error) {
                refusedFast(error);
                if (!error.getMessage().equals(lastError)) {
                    LOG.warnv("Waiting for {0}: {1}", what, error.getMessage());
                    lastError = error.getMessage();
                }
            }
            if (!clock.instant().plus(poll).isBefore(deadline)) {
                return Optional.empty();
            }
            Thread.sleep(poll.toMillis());
        }
    }

    /** Fixture stock must exist; otherwise the run fails with a plain message. */
    public InventoryApi.Stock requireStock(String siteId, String sku) {
        return inventory.stock(siteId, sku, run.correlationId())
                .orElseThrow(() -> new Failure("Fixtures are not seeded: no stock record " + siteId + "/" + sku));
    }

    /** States the order has entered; the number of times a state appears. */
    public long times(List<String> transitions, String state) {
        return transitions.stream().filter(state::equals).count();
    }

    /** A precondition failed; the run ends FAILED with this plain message. */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }
}
