package org.box1.scenario.scenarios;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;
import org.box1.scenario.upstream.UpstreamException;

/** "Hold that order, Number One": the request is held in the ERP console, then released. */
@ApplicationScoped
public class ErpManualHold implements ScenarioExecutor {
    static final String SITE = "site_utopia_planitia";
    static final String SKU = "CAT-COMBADGE-COLLAR";
    static final Duration SIGNAL_WAIT = Duration.ofSeconds(45);

    @Override
    public String name() {
        return "erp-manual-hold";
    }

    @Override
    public Result execute(RunContext c) throws InterruptedException {
        c.requireStock(SITE, SKU);
        String orderId = c.orders.place(c.key(1), c.correlationId(), "cust_janeway", SKU, 1, SITE).orderId();
        c.impact("orders", List.of(orderId));
        var requested = Steps.reaches(c, orderId, Steps.PAST_RESERVATION);
        if (requested.isEmpty() || Steps.DEAD_END.contains(requested.get().state())) {
            return Result.notVerified("Order " + orderId + " did not reach FULFILLMENT_REQUESTED; last state " + Steps.last(c, orderId));
        }
        String ful = Steps.fulfillmentId(orderId);
        c.impact("fulfillmentRequests", List.of(ful));
        if (Steps.acknowledged(c, orderId, ful).isEmpty()) {
            return Result.notVerified("The ERP did not acknowledge " + ful + " before the TTL");
        }
        c.awaitOperator("In the ERP console, find " + ful + " (\"/\", " + ful + ", Enter) and hold it (\"h\", a reason, Enter).");
        var held = c.await("hold of " + ful, () -> c.adapter.fulfillment(ful, c.correlationId()).filter(f -> "HELD".equals(f.outcome())));
        if (held.isEmpty()) {
            return Result.notVerified("Operator step not observed before the TTL: " + ful + " was never HELD");
        }
        String whileHeld = Steps.last(c, orderId);
        // The adapter refreshes its gauges periodically (15 s by default), so wait for the signal before releasing.
        String signal = c.await("fulfillment_held signal", SIGNAL_WAIT, () -> heldGauge(c) >= 1 ? Optional.of("fulfillment_held >= 1")
                : Optional.empty()).orElse("fulfillment_held not observed within " + SIGNAL_WAIT.toSeconds() + " s");
        c.impact("heldSignal", signal);
        c.note("HELD_OBSERVED", ful + " HELD; order " + whileHeld + "; " + signal);
        c.awaitOperator("In the ERP console, release " + ful + " (\"l\").");
        var released = c.await("release of " + ful, () -> c.adapter.fulfillment(ful, c.correlationId())
                .filter(f -> !"HELD".equals(f.outcome())));
        c.resume();
        if (released.isEmpty()) {
            return Result.notVerified("Operator step not observed before the TTL: " + ful + " is still HELD")
                    .withGuidedReset("Release " + ful + " in the ERP console (\"l\").");
        }
        String after = Steps.last(c, orderId);
        if (!"FULFILLMENT_REQUESTED".equals(whileHeld) || Steps.DEAD_END.contains(after)) {
            return Result.failed("Order " + orderId + " was " + whileHeld + " while held and " + after + " after release");
        }
        return Result.verified("Held and released " + ful + "; order " + orderId + " stayed FULFILLMENT_REQUESTED while held,"
                + " now " + after + "; adapter outcome " + released.get().outcome() + "; held signal: " + signal);
    }

    private static double heldGauge(RunContext c) {
        try {
            return c.adapter.heldGauge();
        } catch (UpstreamException error) {
            return Double.NaN;
        }
    }

}
