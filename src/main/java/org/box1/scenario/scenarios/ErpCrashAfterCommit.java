package org.box1.scenario.scenarios;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;

/**
 * "Warp core breach (one-shot, contained)": the ERP crashes once after committing a shipment and recovers it. The
 * crash and restart are visible only inside the ERP, so the operator confirms two steps the controller cannot see
 * (armed, then crashed and restarted); each confirmation is audited with the operator's identity. The controller
 * verifies the recovered outcome itself: exactly one shipment. Without both confirmations the run is NOT_VERIFIED.
 */
@ApplicationScoped
public class ErpCrashAfterCommit implements ScenarioExecutor {
    static final String SITE = "site_ds9";
    static final String SKU = "CAT-DILITHIUM-NIP";
    static final String UNDO = "Check the ERP status line; if the crash is still armed, disarm it (\"X\", \"2\").";

    @Override
    public String name() {
        return "erp-crash-after-commit";
    }

    @Override
    public Result execute(RunContext c) throws InterruptedException {
        c.requireStock(SITE, SKU);
        if (!c.awaitConfirmation("crash-armed", "In the ERP console, with the ERP in AUTO, arm the one-shot crash"
                + " (\"X\", \"2\", \"Y\", Enter). The order is placed after you confirm.")) {
            return Result.notVerified("Operator step not confirmed before the TTL: crash-armed").withGuidedReset(UNDO);
        }
        String orderId = c.orders.place(c.key(1), c.correlationId(), "cust_sisko", SKU, 1, SITE).orderId();
        c.impact("orders", List.of(orderId));
        var shipped = Steps.reaches(c, orderId, Steps.SHIPPED);
        if (shipped.isEmpty() || Steps.DEAD_END.contains(shipped.get().state())) {
            return Result.notVerified("Order " + orderId + " did not ship before the TTL; last state " + Steps.last(c, orderId))
                    .withGuidedReset(UNDO);
        }
        String ful = Steps.fulfillmentId(orderId);
        c.impact("fulfillmentRequests", List.of(ful));
        var problem = Steps.notShippedOnce(c, orderId, ful);
        if (problem.isPresent()) {
            return Result.failed(problem.get()).withGuidedReset(UNDO);
        }
        if (!c.awaitConfirmation("crash-observed", "Check that the ERP worker exited and restarted while shipping " + ful
                + " (status line, or journalctl -u box1-erp-worker on the VM).")) {
            return Result.notVerified("Shipped exactly once, but the crash was not confirmed before the TTL").withGuidedReset(UNDO);
        }
        return Result.verified("Order " + orderId + " shipped exactly once (" + c.read(() -> c.adapter.fulfillment(ful,
                c.correlationId())).map(f -> f.shipmentId()).orElse("unknown") + ") after an operator-confirmed crash and restart");
    }
}
