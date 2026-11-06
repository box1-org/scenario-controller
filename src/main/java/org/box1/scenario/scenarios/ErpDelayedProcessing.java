package org.box1.scenario.scenarios;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;

/** "Impulse power only": ERP AUTO processing is delayed; the order waits visibly, then ships. */
@ApplicationScoped
public class ErpDelayedProcessing implements ScenarioExecutor {
    static final String SITE = "site_spacedock";
    static final String SKU = "CAT-PHASER-LASER";
    static final String UNDO = "Set the ERP delay back to 0 (\"X\", \"3\", 0, Enter).";

    private final Duration minimumWait;

    public ErpDelayedProcessing(ScenarioSettings settings) {
        this.minimumWait = settings.minimumVisibleDelay();
    }

    @Override
    public String name() {
        return "erp-delayed-processing";
    }

    @Override
    public Result execute(RunContext c) throws InterruptedException {
        c.requireStock(SITE, SKU);
        c.awaitOperator("In the ERP console, switch to AUTO if needed (\"m\", \"Y\") and set a delay of at least 90 seconds"
                + " (\"X\", \"3\", seconds, Enter). The order follows now.");
        String orderId = c.orders.place(c.key(1), c.correlationId(), "cust_data", SKU, 1, SITE).orderId();
        c.impact("orders", List.of(orderId));
        var requested = Steps.reaches(c, orderId, Steps.PAST_RESERVATION);
        if (requested.isEmpty() || Steps.DEAD_END.contains(requested.get().state())) {
            return Result.notVerified("Order " + orderId + " did not reach FULFILLMENT_REQUESTED; last state "
                    + Steps.last(c, orderId)).withGuidedReset(UNDO);
        }
        String ful = Steps.fulfillmentId(orderId);
        c.impact("fulfillmentRequests", List.of(ful));
        if (Steps.acknowledged(c, orderId, ful).isEmpty()) {
            return Result.notVerified("The ERP did not acknowledge " + ful + " before the TTL").withGuidedReset(UNDO);
        }
        Instant acknowledged = c.now();
        c.note("ACKNOWLEDGED", ful);
        var shipped = Steps.reaches(c, orderId, Steps.SHIPPED);
        c.resume();
        if (shipped.isEmpty() || Steps.DEAD_END.contains(shipped.get().state())) {
            return Result.notVerified("Order " + orderId + " did not ship before the TTL; last state " + Steps.last(c, orderId)
                    + ". Is the ERP in AUTO?").withGuidedReset(UNDO);
        }
        Duration waited = Duration.between(acknowledged, c.now());
        if (waited.compareTo(minimumWait) < 0) {
            return Result.notVerified("Order " + orderId + " shipped " + waited.toSeconds()
                    + " s after acknowledgement; no visible delay").withGuidedReset(UNDO);
        }
        long shipments = c.times(c.orders.transitions(orderId, c.correlationId()), "SHIPPED");
        if (shipments != 1) {
            return Result.failed("Order " + orderId + " has " + shipments + " SHIPPED transitions").withGuidedReset(UNDO);
        }
        return Result.verified("Order " + orderId + " waited at least " + waited.toSeconds()
                + " s in FULFILLMENT_REQUESTED after ERP acknowledgement, then shipped once").withGuidedReset(UNDO);
    }
}
