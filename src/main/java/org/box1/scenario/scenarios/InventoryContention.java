package org.box1.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.fixtures.Fixtures;
import org.box1.scenario.run.RunContext;
import org.box1.scenario.run.ScenarioExecutor;
import org.box1.scenario.upstream.OrderApi;

/**
 * "The Trouble with Tribbles": Kirk and Sisko order the last tribble at DS9 at once; exactly one reservation wins.
 * Both order everything that is available (normally the one tribble), so the race stays exact even if available stock
 * ever ends above one: reset can only add stock, never reduce it (D-003).
 */
@ApplicationScoped
public class InventoryContention implements ScenarioExecutor {
    static final String SITE = "site_ds9";
    static final String SKU = "CAT-TRIBBLE-TOY";

    @Override
    public String name() {
        return "inventory-contention";
    }

    @Override
    public Result execute(RunContext c) throws InterruptedException {
        var stock = c.requireStock(SITE, SKU);
        if (stock.available() == 0) {
            var restored = c.fixtures.restore(List.of(Fixtures.plan(SITE, SKU)), c.runId() + "-setup", c.correlationId());
            c.note("FIXTURE_RESTORED", restored.toString());
            stock = c.requireStock(SITE, SKU);
        }
        int last = stock.available();
        if (last < 1) {
            return Result.failed("No " + SKU + " available at " + SITE + " after restoring the fixture");
        }
        c.impact("contested", Integer.toString(last));
        List<OrderApi.Placed> placed = placeTogether(c, last);
        List<String> ids = placed.stream().map(OrderApi.Placed::orderId).toList();
        c.impact("orders", ids);
        c.note("ORDERS_PLACED", String.join(",", ids) + "; each for " + last);
        List<OrderApi.Order> decided = new ArrayList<>();
        for (String id : ids) {
            var order = Steps.decided(c, id);
            if (order.isEmpty()) {
                return Result.notVerified("Order " + id + " stayed SUBMITTED until the TTL");
            }
            decided.add(order.get());
        }
        List<OrderApi.Order> won = decided.stream().filter(o -> Steps.PAST_RESERVATION.contains(o.state())).toList();
        List<OrderApi.Order> lost = decided.stream()
                .filter(o -> "REJECTED".equals(o.state()) && "INSUFFICIENT_STOCK".equals(o.statusReason())).toList();
        int available = c.read(() -> c.requireStock(SITE, SKU)).available();
        String states = decided.stream().map(o -> o.orderId() + "=" + o.state() + "/" + o.statusReason()).toList().toString();
        if (won.size() != 1 || lost.size() != 1 || available != 0) {
            return Result.failed("Invariant broken: " + states + ", available " + available);
        }
        return Result.verified("One reservation won (" + won.get(0).orderId() + ") for the last " + (last == 1 ? "tribble" : last
                + " tribbles") + "; " + lost.get(0).orderId() + " REJECTED INSUFFICIENT_STOCK; " + SITE + " " + SKU + " available 0");
    }

    /** Both creates leave together, released by one latch. */
    private static List<OrderApi.Placed> placeTogether(RunContext c, int quantity) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<OrderApi.Placed> kirk = pool.submit(() -> {
                go.await();
                return c.orders.place(c.key(1), c.correlationId(), "cust_kirk", SKU, quantity, SITE);
            });
            Future<OrderApi.Placed> sisko = pool.submit(() -> {
                go.await();
                return c.orders.place(c.key(2), c.correlationId(), "cust_sisko", SKU, quantity, SITE);
            });
            go.countDown();
            List<OrderApi.Placed> placed = new ArrayList<>();
            RuntimeException failure = null;
            for (Future<OrderApi.Placed> f : List.of(kirk, sisko)) {
                try {
                    placed.add(f.get());
                } catch (ExecutionException error) {
                    failure = error.getCause() instanceof RuntimeException r ? r : new IllegalStateException(error.getCause());
                }
            }
            if (failure != null) {
                // Record what was placed before failing, so the audit names every order the run created.
                c.impact("orders", placed.stream().map(OrderApi.Placed::orderId).toList());
                throw failure;
            }
            return placed;
        } finally {
            pool.shutdownNow();
        }
    }
}
