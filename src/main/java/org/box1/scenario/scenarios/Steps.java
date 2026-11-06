package org.box1.scenario.scenarios;

import java.util.Optional;
import java.util.Set;

import org.box1.scenario.run.RunContext;
import org.box1.scenario.upstream.AdapterApi;
import org.box1.scenario.upstream.OrderApi;

/** Waits shared by the scenarios. Each returns empty when the run's TTL passes first. */
final class Steps {
    static final Set<String> PAST_RESERVATION = Set.of("RESERVED", "FULFILLMENT_REQUESTED", "SHIPPED", "COMPLETED");
    static final Set<String> SHIPPED = Set.of("SHIPPED", "COMPLETED");
    static final Set<String> DEAD_END = Set.of("REJECTED", "EXCEPTION", "CANCELLED");

    private Steps() {}

    /** Wait until the order leaves SUBMITTED. */
    static Optional<OrderApi.Order> decided(RunContext c, String orderId) throws InterruptedException {
        return c.await("order " + orderId + " decision", () -> {
            OrderApi.Order order = c.orders.get(orderId, c.correlationId());
            return "SUBMITTED".equals(order.state()) ? Optional.empty() : Optional.of(order);
        });
    }

    /** Wait until the order is in one of the states, or a dead end. */
    static Optional<OrderApi.Order> reaches(RunContext c, String orderId, Set<String> states) throws InterruptedException {
        return c.await("order " + orderId + " " + states, () -> {
            OrderApi.Order order = c.orders.get(orderId, c.correlationId());
            return states.contains(order.state()) || DEAD_END.contains(order.state()) ? Optional.of(order) : Optional.empty();
        });
    }

    /** The adapter's request for the order. Fails the run if the adapter's request belongs to another order. */
    static Optional<AdapterApi.Fulfillment> acknowledged(RunContext c, String orderId, String fulfillmentId)
            throws InterruptedException {
        return c.await("ERP acknowledgement of " + fulfillmentId, () -> c.adapter.fulfillment(fulfillmentId, c.correlationId())
                .map(f -> {
                    if (!orderId.equals(f.orderId())) {
                        throw new RunContext.Failure("Fulfillment request " + fulfillmentId + " does not belong to order " + orderId);
                    }
                    return f;
                })
                .filter(f -> "ACKNOWLEDGED".equals(f.erpAcknowledgement())));
    }

    /**
     * After the order first shows SHIPPED, watch for the settle window (bounded by the TTL), then require exactly one
     * shipment.confirmed.v1 event (by event ID) on the order's timeline (a duplicate is recorded as a note, not a transition), no EXCEPTION,
     * and an adapter shipment ID that did not change. Returns the problem, or empty when shipped exactly once.
     */
    static Optional<String> notShippedOnce(RunContext c, String orderId, String fulfillmentId) throws InterruptedException {
        String before = c.read(() -> c.adapter.fulfillment(fulfillmentId, c.correlationId())).map(AdapterApi.Fulfillment::shipmentId)
                .orElse(null);
        c.await("settle after shipment of " + orderId, c.settle(), Optional::empty);
        var timeline = c.read(() -> c.orders.timeline(orderId, c.correlationId()));
        // One shipment event can move the order through SHIPPED and COMPLETED, so count distinct events.
        long confirmations = timeline.stream().filter(e -> "shipment.confirmed.v1".equals(e.eventType()))
                .map(e -> e.eventId() == null ? e.toString() : e.eventId()).distinct().count();
        String state = c.read(() -> c.orders.get(orderId, c.correlationId())).state();
        var after = c.read(() -> c.adapter.fulfillment(fulfillmentId, c.correlationId()));
        String shipment = after.map(AdapterApi.Fulfillment::shipmentId).orElse(null);
        if (confirmations != 1) {
            return Optional.of("Order " + orderId + " received " + confirmations + " shipment confirmations");
        }
        if ("EXCEPTION".equals(state)) {
            return Optional.of("Order " + orderId + " is in EXCEPTION after shipping");
        }
        if (shipment == null || (before != null && !before.equals(shipment)) || !"SHIPPED".equals(after.get().outcome())) {
            return Optional.of("Fulfillment " + fulfillmentId + " shipment changed or is missing: " + before + " -> " + shipment);
        }
        return Optional.empty();
    }

    static String fulfillmentId(String orderId) {
        return OrderApi.fulfillmentRequestId(orderId)
                .orElseThrow(() -> new RunContext.Failure("Order " + orderId + " has no fulfillment request ID"));
    }

    static String last(RunContext c, String orderId) {
        try {
            return c.orders.get(orderId, c.correlationId()).state();
        } catch (RuntimeException error) {
            return "unknown";
        }
    }
}
