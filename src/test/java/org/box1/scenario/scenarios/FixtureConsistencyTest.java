package org.box1.scenario.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.box1.scenario.catalog.Catalog;
import org.box1.scenario.catalog.Scenario;
import org.box1.scenario.fixtures.FixtureService;
import org.junit.jupiter.api.Test;

/** Each scenario's code uses exactly the stock record its catalog fixtures name, which is what reset restores. */
class FixtureConsistencyTest {
    @Test
    void codeAndCatalogNameTheSameStockRecord() {
        Map<String, List<String>> code = Map.of(
                "baseline-order", List.of(BaselineOrder.SITE, BaselineOrder.SKU),
                "inventory-contention", List.of(InventoryContention.SITE, InventoryContention.SKU),
                "erp-manual-hold", List.of(ErpManualHold.SITE, ErpManualHold.SKU),
                "erp-delayed-processing", List.of(ErpDelayedProcessing.SITE, ErpDelayedProcessing.SKU),
                "erp-crash-after-commit", List.of(ErpCrashAfterCommit.SITE, ErpCrashAfterCommit.SKU));
        for (Scenario s : new Catalog().all()) {
            if (!s.available()) {
                continue;
            }
            List<String> used = code.get(s.name());
            var records = FixtureService.recordsFor(s.fixtures());
            assertTrue(records.size() == 1 && records.get(0).siteId().equals(used.get(0)) && records.get(0).sku().equals(used.get(1)),
                    s.name() + " restores " + records + " but uses " + used);
        }
    }
}
