package org.box1.scenario.fixtures;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.upstream.InventoryApi;

/**
 * Seeds the fixture set through inventory's own API, and restores fixture stock. Only fixture IDs are ever touched;
 * nothing is deleted, released or reduced (D-003, D-007).
 */
@ApplicationScoped
public class FixtureService {
    static final String SEED_REASON = "Starfleet Feline Supply fixture seed (box1-docs#19)";

    private final InventoryApi inventory;

    public FixtureService(InventoryApi inventory) {
        this.inventory = inventory;
    }

    public record SeedResult(int created, int unchanged) {}

    /** Create every fixture site and stock record once. A second pass changes nothing. */
    public SeedResult seed(String correlationId) {
        int created = 0;
        int unchanged = 0;
        for (Fixtures.Site site : Fixtures.SITES) {
            if (inventory.createSite(site.siteId(), site.status(), SEED_REASON, correlationId) == InventoryApi.Created.CREATED) {
                created++;
            } else {
                unchanged++;
            }
        }
        for (Fixtures.StockPlan plan : Fixtures.STOCK) {
            if (inventory.createStock(plan.siteId(), plan.sku(), plan.openingQuantity(), SEED_REASON, correlationId)
                    == InventoryApi.Created.CREATED) {
                created++;
            } else {
                unchanged++;
            }
        }
        return new SeedResult(created, unchanged);
    }

    /** One restored record: what was available before and after. */
    public record Restored(String siteId, String sku, int before, int after) {}

    /**
     * Restock each listed fixture record whose available stock is below its opening quantity, by the difference.
     * Keys are scoped to the reset, so a retry of the same reset changes nothing further.
     */
    public List<Restored> restore(List<Fixtures.StockPlan> records, String resetId, String correlationId) {
        List<Restored> restored = new ArrayList<>();
        for (Fixtures.StockPlan plan : records) {
            if (!Fixtures.isFixtureStock(plan.siteId(), plan.sku())) {
                throw new IllegalArgumentException("Not a fixture stock record");
            }
            var stock = inventory.stock(plan.siteId(), plan.sku(), correlationId);
            if (stock.isEmpty()) {
                continue;
            }
            int available = stock.get().available();
            if (available < plan.openingQuantity()) {
                var after = inventory.restock(plan.siteId(), plan.sku(), plan.openingQuantity() - available,
                        "Scenario reset " + resetId, "stardock-reset-" + resetId + "-" + plan.siteId() + "-" + plan.sku(),
                        correlationId);
                restored.add(new Restored(plan.siteId(), plan.sku(), available, after.available()));
            }
        }
        return restored;
    }

    /** The fixture stock records a scenario's fixture IDs name (sites crossed with SKUs). */
    public static List<Fixtures.StockPlan> recordsFor(List<String> fixtureIds) {
        return Fixtures.STOCK.stream().filter(p -> fixtureIds.contains(p.siteId()) && fixtureIds.contains(p.sku())).toList();
    }
}
