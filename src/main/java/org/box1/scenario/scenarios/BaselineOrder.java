package org.box1.scenario.scenarios;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;

/** "Make it so": Picard orders Spot's bowl from Spacedock; it ships and completes. */
@ApplicationScoped
public class BaselineOrder implements ScenarioExecutor {
    static final String SITE = "site_spacedock";
    static final String SKU = "CAT-SPOT-BOWL";

    @Override
    public String name() {
        return "baseline-order";
    }

    @Override
    public Result execute(RunContext c) throws InterruptedException {
        c.requireStock(SITE, SKU);
        var placed = c.orders.place(c.key(1), c.correlationId(), "cust_picard", SKU, 1, SITE);
        String orderId = placed.orderId();
        c.impact("orders", List.of(orderId));
        c.note("ORDER_PLACED", orderId);
        var replay = c.orders.place(c.key(1), c.correlationId(), "cust_picard", SKU, 1, SITE);
        if (!orderId.equals(replay.orderId())) {
            return Result.failed("A replay of key " + c.key(1) + " returned order " + replay.orderId() + ", not " + orderId);
        }
        var requested = Steps.reaches(c, orderId, Steps.PAST_RESERVATION);
        if (requested.isEmpty()) {
            return Result.notVerified("Order " + orderId + " stayed " + Steps.last(c, orderId) + " until the TTL");
        }
        if (Steps.DEAD_END.contains(requested.get().state())) {
            return Result.failed("Order " + orderId + " ended " + requested.get().state() + " " + requested.get().statusReason());
        }
        String fulfillmentId = Steps.fulfillmentId(orderId);
        c.impact("fulfillmentRequests", List.of(fulfillmentId));
        if (!Steps.SHIPPED.contains(requested.get().state())) {
            c.awaitOperator("In the ERP console, ship " + fulfillmentId + " (\"/\", " + fulfillmentId + ", Enter, \"s\", \"Y\"),"
                    + " or switch the ERP to AUTO (\"m\", \"Y\").");
        }
        var shipped = Steps.reaches(c, orderId, Steps.SHIPPED);
        c.resume();
        if (shipped.isEmpty()) {
            return Result.notVerified("Order " + orderId + " did not ship before the TTL; last state " + Steps.last(c, orderId));
        }
        if (Steps.DEAD_END.contains(shipped.get().state())) {
            return Result.failed("Order " + orderId + " ended " + shipped.get().state() + " " + shipped.get().statusReason());
        }
        var problem = Steps.notShippedOnce(c, orderId, fulfillmentId);
        if (problem.isPresent()) {
            return Result.failed(problem.get());
        }
        return Result.verified("Order " + orderId + " reached " + shipped.get().state() + "; replay returned the same order");
    }
}
