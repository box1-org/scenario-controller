package org.box1.scenario.scenarios;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;

/**
 * "Warp core breach (one-shot, contained)": the ERP crashes once after committing a shipment and recovers it. The crash
 * is visible only inside the ERP; the controller verifies the recovered outcome: one shipment, no duplicate.
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
        c.awaitOperator("In the ERP console, with the ERP in AUTO, arm the one-shot crash (\"X\", \"2\", \"Y\", Enter)."
                + " The order follows now.");
        String orderId = c.orders.place(c.key(1), c.correlationId(), "cust_sisko", SKU, 1, SITE).orderId();
        c.impact("orders", List.of(orderId));
        var shipped = Steps.reaches(c, orderId, Steps.SHIPPED);
        c.resume();
        if (shipped.isEmpty() || Steps.DEAD_END.contains(shipped.get().state())) {
            return Result.notVerified("Order " + orderId + " did not ship before the TTL; last state " + Steps.last(c, orderId))
                    .withGuidedReset(UNDO);
        }
        String ful = Steps.fulfillmentId(orderId);
        c.impact("fulfillmentRequests", List.of(ful));
        long shipments = c.times(c.orders.transitions(orderId, c.correlationId()), "SHIPPED");
        var status = c.adapter.fulfillment(ful, c.correlationId());
        if (shipments != 1 || status.isEmpty() || !orderId.equals(status.get().orderId())
                || !"SHIPPED".equals(status.get().outcome())) {
            return Result.failed("Order " + orderId + " has " + shipments + " SHIPPED transitions; adapter outcome "
                    + status.map(s -> s.outcome()).orElse("missing")).withGuidedReset(UNDO);
        }
        return Result.verified("Order " + orderId + " shipped exactly once (" + status.get().shipmentId()
                + "). The crash and restart are visible only in the ERP journal").withGuidedReset(UNDO);
    }
}
