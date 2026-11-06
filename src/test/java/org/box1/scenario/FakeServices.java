package org.box1.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * In-JVM stand-ins for order-service, inventory-service, the fulfillment adapter and the ERP, following their contracts:
 * order states (ORDER-STATE.md), inventory's create-once and restock routes, the adapter's status view. Tests act as the
 * ERP operator through {@link #hold}, {@link #release}, {@link #auto} and {@link #delay}. Not a substitute for the real
 * services; the local end-to-end run covers those.
 */
public final class FakeServices {
    static final String ADMIN_TOKEN = "test-admin";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    final Map<String, String> sites = new LinkedHashMap<>();
    public final Map<String, int[]> stock = new LinkedHashMap<>();
    final Map<String, Order> orders = new LinkedHashMap<>();
    final Map<String, String> keys = new HashMap<>();
    final Map<String, Ful> fulfillments = new LinkedHashMap<>();
    final List<String> writes = Collections.synchronizedList(new ArrayList<>());
    volatile boolean autoMode;
    volatile long delayMillis;
    volatile boolean erpReachable = true;
    volatile boolean decide = true;
    volatile boolean duplicateShipment;

    static final class Order {
        String orderId;
        String customerId;
        String sku;
        int quantity;
        String siteId;
        String state = "SUBMITTED";
        String statusReason = "ORDER_PLACED";
        final List<String> transitions = new ArrayList<>(List.of("SUBMITTED"));
        /** Timeline entries: kind|toState|eventType. */
        final List<String[]> timeline = new ArrayList<>(List.<String[]>of(new String[] {"TRANSITION", "SUBMITTED", "order.placed.v1", "evt_0"}));
    }

    static final class Ful {
        String id;
        String orderId;
        String ack = "NONE";
        String outcome = "AWAITING_ERP";
        String shipmentId;
        long createdMillis = System.currentTimeMillis();
        boolean held;
        boolean rejected;
        String acknowledgedAt;
        String statusAt;
    }

    FakeServices() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        reset();
        server.start();
        ticker.scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void close() {
        ticker.shutdownNow();
        server.stop(0);
    }

    /** Lab seed only, as a fresh inventory has. */
    public synchronized void reset() {
        sites.clear();
        stock.clear();
        orders.clear();
        keys.clear();
        fulfillments.clear();
        writes.clear();
        opening.clear();
        restockKeys.clear();
        sites.put("site_nyc", "ACTIVE");
        sites.put("site_bos", "ACTIVE");
        sites.put("site_closed", "UNAVAILABLE");
        stock.put("site_nyc|SKU-42", new int[] {100, 0});
        stock.put("site_nyc|SKU-LAST", new int[] {1, 0});
        autoMode = false;
        delayMillis = 0;
        erpReachable = true;
        decide = true;
        duplicateShipment = false;
    }

    synchronized void reject(String ful) {
        fulfillments.get(ful).rejected = true;
    }

    synchronized void hold(String ful) {
        fulfillments.get(ful).held = true;
    }

    synchronized void release(String ful) {
        fulfillments.get(ful).held = false;
    }

    void auto() {
        autoMode = true;
    }

    void delay(long millis) {
        delayMillis = millis;
    }

    synchronized Map<String, int[]> labSeedSnapshot() {
        Map<String, int[]> copy = new LinkedHashMap<>();
        stock.forEach((k, v) -> {
            if (k.startsWith("site_nyc|") || k.startsWith("site_bos|") || k.startsWith("site_closed|")) {
                copy.put(k, v.clone());
            }
        });
        return copy;
    }

    private synchronized void tick() {
        if (decide) {
            for (Order o : orders.values()) {
                if ("SUBMITTED".equals(o.state)) {
                    decide(o);
                }
            }
        }
        if (!erpReachable) {
            return;
        }
        for (Ful f : fulfillments.values()) {
            if ("NONE".equals(f.ack)) {
                f.ack = "ACKNOWLEDGED";
                f.outcome = "PENDING";
                f.acknowledgedAt = java.time.Instant.now().toString();
                f.statusAt = f.acknowledgedAt;
            }
            if ("SHIPPED".equals(f.outcome) || "REJECTED".equals(f.outcome)) {
                continue;
            }
            if (f.rejected) {
                f.outcome = "REJECTED";
                f.statusAt = java.time.Instant.now().toString();
                continue;
            }
            f.outcome = f.held ? "HELD" : "PENDING";
            if (!f.held && autoMode && System.currentTimeMillis() - f.createdMillis >= delayMillis) {
                Order o = orders.get(f.orderId);
                int[] s = stock.get(o.siteId + "|" + o.sku);
                s[0] -= o.quantity;
                s[1] -= o.quantity;
                f.outcome = "SHIPPED";
                f.shipmentId = "shp_" + f.id.substring(4);
                f.statusAt = java.time.Instant.now().toString();
                String shipmentEvent = UUID.randomUUID().toString();
                move(o, "SHIPPED", "SHIPMENT_CONFIRMED", "shipment.confirmed.v1", shipmentEvent);
                move(o, "COMPLETED", "SHIPMENT_CONFIRMED", "shipment.confirmed.v1", shipmentEvent);
                if (duplicateShipment) {
                    o.timeline.add(new String[] {"NOTE", null, "shipment.confirmed.v1", UUID.randomUUID().toString()});
                }
            }
        }
    }

    private void decide(Order o) {
        String site = sites.get(o.siteId);
        int[] s = stock.get(o.siteId + "|" + o.sku);
        if (site == null || "UNAVAILABLE".equals(site)) {
            move(o, "REJECTED", "SITE_UNAVAILABLE");
        } else if (s == null) {
            move(o, "REJECTED", "UNKNOWN_SKU");
        } else if (s[0] - s[1] < o.quantity) {
            move(o, "REJECTED", "INSUFFICIENT_STOCK");
        } else {
            s[1] += o.quantity;
            move(o, "RESERVED", "INVENTORY_RESERVED");
            move(o, "FULFILLMENT_REQUESTED", "INVENTORY_RESERVED");
            Ful f = new Ful();
            f.id = "ful_" + o.orderId.substring(4);
            f.orderId = o.orderId;
            fulfillments.put(f.id, f);
        }
    }

    private static void move(Order o, String state, String reason) {
        move(o, state, reason, state.equals("REJECTED") || state.equals("RESERVED") ? "inventory." + (state.equals("REJECTED")
                ? "rejected" : "reserved") + ".v1" : "order.state-changed.v1");
    }

    private static void move(Order o, String state, String reason, String eventType) {
        move(o, state, reason, eventType, UUID.randomUUID().toString());
    }

    private static void move(Order o, String state, String reason, String eventType, String eventId) {
        o.state = state;
        o.statusReason = reason;
        o.transitions.add(state);
        o.timeline.add(new String[] {"TRANSITION", state, eventType, eventId});
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            String method = x.getRequestMethod();
            String path = x.getRequestURI().getPath();
            if (!"GET".equals(method)) {
                writes.add(method + " " + path);
            }
            synchronized (this) {
                route(x, method, path);
            }
        }
    }

    private void route(HttpExchange x, String method, String path) throws IOException {
        String[] p = path.split("/");
        if ("POST".equals(method) && path.equals("/token")) {
            send(x, 200, Map.of("access_token", ADMIN_TOKEN, "expires_in", 300));
        } else if ("POST".equals(method) && path.equals("/api/v1/orders")) {
            placeOrder(x);
        } else if ("GET".equals(method) && path.matches("/api/v1/orders/ord_[A-Za-z0-9_-]+")) {
            Order o = orders.get(p[4]);
            send(x, o == null ? 404 : 200, o == null ? Map.of() : view(o));
        } else if ("GET".equals(method) && path.matches("/api/v1/orders/ord_[A-Za-z0-9_-]+/timeline")) {
            Order o = orders.get(p[4]);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String[] e : o.timeline) {
                Map<String, Object> entry = new HashMap<>(Map.of("kind", e[0], "eventType", e[2], "eventId", e[3]));
                if (e[1] != null) {
                    entry.put("toState", e[1]);
                }
                entries.add(entry);
            }
            send(x, 200, entries);
        } else if ("GET".equals(method) && path.equals("/api/v1/inventory")) {
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            List<Map<String, Object>> rows = new ArrayList<>();
            stock.forEach((k, v) -> {
                String[] ks = k.split("\\|");
                if ((q.get("siteId") == null || q.get("siteId").equals(ks[0])) && (q.get("sku") == null || q.get("sku").equals(ks[1]))) {
                    rows.add(Map.of("siteId", ks[0], "sku", ks[1], "onHand", v[0], "reserved", v[1], "available", v[0] - v[1]));
                }
            });
            send(x, 200, rows);
        } else if (path.startsWith("/internal/v1/") && !("Bearer " + ADMIN_TOKEN).equals(x.getRequestHeaders().getFirst("Authorization"))) {
            send(x, 401, Map.of("title", "Unauthorized"));
        } else if ("PUT".equals(method) && path.matches("/internal/v1/sites/site_[A-Za-z0-9_-]+")) {
            JsonNode body = body(x);
            String existing = sites.get(p[4]);
            if (existing == null) {
                sites.put(p[4], body.get("status").asText());
                send(x, 201, Map.of("siteId", p[4], "status", body.get("status").asText()));
            } else {
                send(x, existing.equals(body.get("status").asText()) ? 200 : 409, Map.of("siteId", p[4], "status", existing));
            }
        } else if ("PUT".equals(method) && path.matches("/internal/v1/stock/site_[A-Za-z0-9_-]+/[A-Za-z0-9_-]+")) {
            int q = body(x).get("openingQuantity").asInt();
            String k = p[4] + "|" + p[5];
            if (!sites.containsKey(p[4])) {
                send(x, 404, Map.of());
            } else if (stock.containsKey(k)) {
                send(x, opening.getOrDefault(k, -1) == q ? 200 : 409, Map.of());
            } else {
                stock.put(k, new int[] {q, 0});
                opening.put(k, q);
                send(x, 201, Map.of());
            }
        } else if ("POST".equals(method) && path.matches("/internal/v1/stock/site_[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/restock")) {
            int q = body(x).get("quantity").asInt();
            int[] s = stock.get(p[4] + "|" + p[5]);
            String key = x.getRequestHeaders().getFirst("Idempotency-Key");
            if (s == null) {
                send(x, 404, Map.of());
                return;
            }
            if (restockKeys.add(key)) {
                s[0] += q;
            }
            send(x, 200, Map.of("onHand", s[0], "reserved", s[1], "available", s[0] - s[1]));
        } else if ("GET".equals(method) && path.matches("/api/fulfillments/ful_[A-Za-z0-9_-]+")) {
            Ful f = fulfillments.get(p[3]);
            if (f == null) {
                send(x, 404, Map.of());
            } else {
                Map<String, Object> v = new HashMap<>(Map.of("fulfillmentRequestId", f.id, "orderId", f.orderId,
                        "dispatch", "UPLOADED", "erpAcknowledgement", f.ack, "outcome", f.outcome));
                if (f.shipmentId != null) {
                    v.put("shipmentId", f.shipmentId);
                }
                if (f.acknowledgedAt != null) {
                    v.put("acknowledgedAt", f.acknowledgedAt);
                    v.put("statusAt", f.statusAt);
                }
                send(x, 200, v);
            }
        } else if ("GET".equals(method) && path.equals("/q/metrics")) {
            long held = fulfillments.values().stream().filter(f -> "HELD".equals(f.outcome)).count();
            byte[] text = ("# TYPE fulfillment_held gauge\nfulfillment_held " + held + ".0\n").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, text.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(text);
            }
        } else {
            send(x, 404, Map.of());
        }
    }

    final Map<String, Integer> opening = new HashMap<>();
    final java.util.Set<String> restockKeys = new java.util.HashSet<>();

    private void placeOrder(HttpExchange x) throws IOException {
        JsonNode b = body(x);
        String key = x.getRequestHeaders().getFirst("X-Consumer-Id") + "|" + x.getRequestHeaders().getFirst("Idempotency-Key");
        String payload = b.toString();
        if (keys.containsKey(key)) {
            String[] known = keys.get(key).split("\\|", 2);
            send(x, known[1].equals(payload) ? 200 : 409, view(orders.get(known[0])));
            return;
        }
        Order o = new Order();
        o.orderId = "ord_" + UUID.randomUUID().toString().replace("-", "");
        o.customerId = b.get("customerId").asText();
        o.sku = b.get("sku").asText();
        o.quantity = b.get("quantity").asInt();
        o.siteId = b.get("siteId").asText();
        orders.put(o.orderId, o);
        keys.put(key, o.orderId + "|" + payload);
        send(x, 201, view(o));
    }

    private static Map<String, Object> view(Order o) {
        return Map.of("orderId", o.orderId, "state", o.state, "statusReason", o.statusReason, "customerId", o.customerId,
                "sku", o.sku, "quantity", o.quantity, "siteId", o.siteId);
    }

    private static JsonNode body(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            return JSON.readTree(in);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                q.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return q;
    }

    private static void send(HttpExchange x, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }
}
