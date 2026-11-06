package org.box1.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.box1.scenario.catalog.Catalog;
import org.box1.scenario.catalog.Scenario;
import org.box1.scenario.fixtures.Fixtures;
import org.junit.jupiter.api.Test;

/** The catalog and fixture set match the prompt (09_scenario_controller.md) and the contract ID patterns. */
class CatalogTest {
    private static final Pattern CUSTOMER = Pattern.compile("^cust_[A-Za-z0-9_-]{1,59}$");
    private static final Pattern SITE = Pattern.compile("^site_[A-Za-z0-9_-]{1,59}$");
    private static final Pattern ASSET = Pattern.compile("^asset_[A-Za-z0-9_-]+$");

    @Test
    void everyScenarioHasItsTitleAndClassifiedMechanism() {
        Map<String, String> titles = new LinkedHashMap<>();
        titles.put("baseline-order", "Make it so|SERVICE_API|AVAILABLE");
        titles.put("inventory-contention", "The Trouble with Tribbles|SERVICE_API|AVAILABLE");
        titles.put("erp-manual-hold", "Hold that order, Number One|GUIDED_ERP|AVAILABLE");
        titles.put("erp-delayed-processing", "Impulse power only|GUIDED_ERP|AVAILABLE");
        titles.put("erp-crash-after-commit", "Warp core breach (one-shot, contained)|GUIDED_ERP|AVAILABLE");
        titles.put("erp-unavailable", "The ERP has cloaked|VM_LEVEL|NOT_YET_POSSIBLE");
        titles.put("broker-outage", "Subspace interference|PLATFORM|NOT_YET_POSSIBLE");
        titles.put("poison-event", "Q was here|PLATFORM|NOT_YET_POSSIBLE");
        titles.put("bad-artifact-gate", "Unsigned shuttlecraft denied docking|PLATFORM|NOT_YET_POSSIBLE");
        titles.put("red-alert", "Red alert|SERVICE_API|NOT_YET_POSSIBLE");
        Catalog catalog = new Catalog();
        assertEquals(titles.keySet().stream().toList(), catalog.all().stream().map(Scenario::name).toList());
        for (Scenario s : catalog.all()) {
            assertEquals(titles.get(s.name()), s.title() + "|" + s.mechanism() + "|" + s.availability(), s.name());
            assertTrue(s.maxDurationSeconds() <= 3600, "bounded duration " + s.name());
            if (!s.available()) {
                assertFalse(s.notPossibleReason().isBlank(), s.name());
            }
            if (s.mechanism() == Scenario.Mechanism.GUIDED_ERP) {
                assertFalse(s.operatorSteps().isEmpty(), "guided scenarios state the console step " + s.name());
            }
        }
    }

    @Test
    void fixtureIdsMatchTheContractPatterns() {
        Fixtures.CUSTOMERS.forEach(c -> assertTrue(CUSTOMER.matcher(c.customerId()).matches(), c.customerId()));
        Fixtures.SITES.forEach(s -> assertTrue(SITE.matcher(s.siteId()).matches(), s.siteId()));
        Fixtures.ASSETS.keySet().forEach(a -> assertTrue(ASSET.matcher(a).matches(), a));
        Fixtures.PRODUCTS.forEach(p -> assertTrue(p.sku().length() <= 80 && p.sku().startsWith("CAT-"), p.sku()));
        assertEquals(4, Fixtures.SITES.size());
        assertEquals(5, Fixtures.CUSTOMERS.size());
        assertEquals(9, Fixtures.PRODUCTS.size());
        assertEquals(1, Fixtures.plan("site_ds9", "CAT-TRIBBLE-TOY").openingQuantity(), "the last tribble");
        assertTrue(Fixtures.STOCK.stream().noneMatch(p -> p.siteId().equals("site_neutral_zone")));
        assertTrue(Fixtures.STOCK.stream().noneMatch(p -> p.siteId().matches("site_(nyc|bos|closed)")), "lab seed untouched");
    }

    @Test
    void everyScenarioFixtureIsInTheFixtureSet() {
        for (Scenario s : new Catalog().all()) {
            for (String id : s.fixtures()) {
                boolean known = Fixtures.SITES.stream().anyMatch(x -> x.siteId().equals(id))
                        || Fixtures.CUSTOMERS.stream().anyMatch(x -> x.customerId().equals(id))
                        || Fixtures.PRODUCTS.stream().anyMatch(x -> x.sku().equals(id))
                        || Fixtures.ASSETS.containsKey(id);
                assertTrue(known, s.name() + " names " + id);
            }
        }
    }

    @Test
    void stardateIsDisplayOnlyAndDeterministic() {
        assertEquals("-297000.0", Stardate.of(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals("-296153.4", Stardate.of(java.time.Instant.parse("2026-11-06T00:00:00Z")));
        assertEquals("0.0", Stardate.of(java.time.Instant.parse("2323-01-01T00:00:00Z")));
    }
}
