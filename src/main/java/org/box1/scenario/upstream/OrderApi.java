package org.box1.scenario.upstream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;

/** Order service (no API authentication, box1-docs#40). Create, read and timeline only. */
@ApplicationScoped
public class OrderApi {
    static final String CONSUMER = "scenario-controller";
    private static final Pattern ORDER_ID = Pattern.compile("^ord_[A-Za-z0-9_-]{1,124}$");

    private final Http http;
    private final String base;

    public OrderApi(Http http, ScenarioSettings settings) {
        this.http = http;
        this.base = settings.orderUrl();
    }

    public record Placed(int status, String orderId, String state) {}

    public record Order(String orderId, String state, String statusReason) {}

    /**
     * Create an order. 201 = created, 200 = replay of the same key and payload. A transport failure or 5xx is retried
     * with the same key (up to three attempts), which the idempotency contract makes safe: a create that was saved
     * before the failure is replayed, not duplicated.
     */
    public Placed place(String key, String correlationId, String customerId, String sku, int quantity, String siteId) {
        Map<String, Object> body = Map.of("customerId", customerId, "sku", sku, "quantity", quantity, "siteId", siteId);
        Http.Reply reply = null;
        for (int attempt = 1; reply == null; attempt++) {
            try {
                reply = http.send("POST", base, "/api/v1/orders", body,
                        Map.of("Idempotency-Key", key, "X-Consumer-Id", CONSUMER), correlationId);
                if (reply.status() >= 500 && attempt < 3) {
                    reply = null;
                }
            } catch (UpstreamException error) {
                if (attempt >= 3) {
                    throw error;
                }
            }
            if (reply == null) {
                pause();
            }
        }
        if (reply.status() != 201 && reply.status() != 200) {
            throw new UpstreamException("Order service answered " + reply.status() + " to an order create", reply.status());
        }
        String orderId = text(reply.body(), "orderId");
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
            throw new UpstreamException("Order service returned no valid order ID", reply.status());
        }
        return new Placed(reply.status(), orderId, text(reply.body(), "state"));
    }

    public Order get(String orderId, String correlationId) {
        Http.Reply reply = http.send("GET", base, "/api/v1/orders/" + Http.segment(orderId), null, Map.of(), correlationId);
        if (reply.status() != 200) {
            throw new UpstreamException("Order service answered " + reply.status() + " to an order read", reply.status());
        }
        return new Order(orderId, text(reply.body(), "state"), text(reply.body(), "statusReason"));
    }

    /** One timeline entry: a transition (toState set) or a note, with the event that caused it. */
    public record Entry(String kind, String toState, String reasonCode, String eventType, String eventId) {}

    /** The whole timeline, transitions and notes, in order. */
    public List<Entry> timeline(String orderId, String correlationId) {
        Http.Reply reply = http.send("GET", base, "/api/v1/orders/" + Http.segment(orderId) + "/timeline", null, Map.of(),
                correlationId);
        if (reply.status() != 200 || reply.body() == null || !reply.body().isArray()) {
            throw new UpstreamException("Order service answered " + reply.status() + " to a timeline read", reply.status());
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode e : reply.body()) {
            entries.add(new Entry(text(e, "kind"), text(e, "toState"), text(e, "reasonCode"), text(e, "eventType"), text(e, "eventId")));
        }
        return entries;
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Order create was interrupted", error);
        }
    }

    /** States the order has entered, in timeline order. */
    public List<String> transitions(String orderId, String correlationId) {
        Http.Reply reply = http.send("GET", base, "/api/v1/orders/" + Http.segment(orderId) + "/timeline", null, Map.of(),
                correlationId);
        if (reply.status() != 200 || reply.body() == null || !reply.body().isArray()) {
            throw new UpstreamException("Order service answered " + reply.status() + " to a timeline read", reply.status());
        }
        List<String> states = new ArrayList<>();
        for (JsonNode entry : reply.body()) {
            if ("TRANSITION".equals(text(entry, "kind")) && entry.hasNonNull("toState")) {
                states.add(entry.get("toState").asText());
            }
        }
        return states;
    }

    /** The fulfillment request ID order-service assigns (internal naming, box1-docs#18 G-1). Callers must cross-check. */
    public static Optional<String> fulfillmentRequestId(String orderId) {
        return orderId.startsWith("ord_") ? Optional.of("ful_" + orderId.substring(4)) : Optional.empty();
    }

    static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
