package org.box1.scenario.upstream;

import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;

/** Inventory service: stock reads (read policy permit) and the create-once and restock admin routes. */
@ApplicationScoped
public class InventoryApi {
    private final Http http;
    private final InventoryToken token;
    private final String base;

    public InventoryApi(Http http, InventoryToken token, ScenarioSettings settings) {
        this.http = http;
        this.token = token;
        this.base = settings.inventoryUrl();
    }

    public record Stock(String siteId, String sku, int onHand, int reserved, int available) {}

    /** Outcome of a create-once call: 201 created, 200 unchanged. */
    public enum Created { CREATED, UNCHANGED }

    public Optional<Stock> stock(String siteId, String sku, String correlationId) {
        Http.Reply reply = http.send("GET", base, "/api/v1/inventory?siteId=" + Http.segment(siteId) + "&sku=" + Http.segment(sku),
                null, Map.of(), correlationId);
        if (reply.status() != 200 || reply.body() == null || !reply.body().isArray()) {
            throw new UpstreamException("Inventory answered " + reply.status() + " to a stock read", reply.status());
        }
        for (var row : reply.body()) {
            if (siteId.equals(row.path("siteId").asText()) && sku.equals(row.path("sku").asText())) {
                return Optional.of(new Stock(siteId, sku, row.path("onHand").asInt(), row.path("reserved").asInt(),
                        row.path("available").asInt()));
            }
        }
        return Optional.empty();
    }

    public Created createSite(String siteId, String status, String reason, String correlationId) {
        return created(http.send("PUT", base, "/internal/v1/sites/" + Http.segment(siteId),
                Map.of("status", status, "reason", reason), auth(), correlationId), "site create");
    }

    public Created createStock(String siteId, String sku, int openingQuantity, String reason, String correlationId) {
        return created(http.send("PUT", base, "/internal/v1/stock/" + Http.segment(siteId) + "/" + Http.segment(sku),
                Map.of("openingQuantity", openingQuantity, "reason", reason), auth(), correlationId), "stock create");
    }

    public Stock restock(String siteId, String sku, int quantity, String reason, String key, String correlationId) {
        Map<String, String> headers = new java.util.HashMap<>(auth());
        headers.put("Idempotency-Key", key);
        Http.Reply reply = http.send("POST", base, "/internal/v1/stock/" + Http.segment(siteId) + "/" + Http.segment(sku) + "/restock",
                Map.of("quantity", quantity, "reason", reason), headers, correlationId);
        if (reply.status() != 200) {
            throw new UpstreamException("Inventory answered " + reply.status() + " to a restock", reply.status());
        }
        var row = reply.body();
        return new Stock(siteId, sku, row.path("onHand").asInt(), row.path("reserved").asInt(), row.path("available").asInt());
    }

    private Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + token.get());
    }

    private static Created created(Http.Reply reply, String what) {
        return switch (reply.status()) {
            case 201 -> Created.CREATED;
            case 200 -> Created.UNCHANGED;
            default -> throw new UpstreamException("Inventory answered " + reply.status() + " to a " + what, reply.status());
        };
    }
}
