package org.box1.scenario.fixtures;

import java.util.List;
import java.util.Map;

/**
 * The Starfleet Feline Supply fixture set (D-002, D-007). IDs are binding and never change; display names may be tuned.
 * Opening quantities are what reset restores available stock to.
 */
public final class Fixtures {
    private Fixtures() {}

    public record Site(String siteId, String displayName, String status) {}

    public record Customer(String customerId, String displayName) {}

    public record Product(String sku, String displayName) {}

    public record StockPlan(String siteId, String sku, int openingQuantity) {}

    public static final List<Site> SITES = List.of(
            new Site("site_spacedock", "Earth Spacedock", "ACTIVE"),
            new Site("site_ds9", "Deep Space Nine, Promenade outlet", "ACTIVE"),
            new Site("site_utopia_planitia", "Utopia Planitia Fleet Yards", "ACTIVE"),
            new Site("site_neutral_zone", "Neutral Zone outpost", "UNAVAILABLE"));

    public static final List<Customer> CUSTOMERS = List.of(
            new Customer("cust_picard", "Captain Picard, USS Enterprise-D"),
            new Customer("cust_data", "Lt. Cmdr. Data"),
            new Customer("cust_janeway", "Captain Janeway, USS Voyager"),
            new Customer("cust_sisko", "Captain Sisko, Deep Space Nine"),
            new Customer("cust_kirk", "Captain Kirk"));

    public static final List<Product> PRODUCTS = List.of(
            new Product("CAT-SPOT-BOWL", "Spot's Feline Supplement bowl"),
            new Product("CAT-PHASER-LASER", "Phaser laser pointer, stun setting only"),
            new Product("CAT-TRIBBLE-TOY", "Tribble plush. Purrs. Does not multiply (we checked)"),
            new Product("CAT-JEFFERIES-TUNNEL", "Jefferies Tube crinkle tunnel"),
            new Product("CAT-HOLODECK-POST", "Holodeck scratching post, safeties engaged"),
            new Product("CAT-CAPTAINS-BED", "Captain's chair cat bed"),
            new Product("CAT-COMBADGE-COLLAR", "Combadge collar that chirps"),
            new Product("CAT-REPLICATOR-FEEDER", "Replicator kibble dispenser"),
            new Product("CAT-DILITHIUM-NIP", "Dilithium catnip crystals"));

    public static final Map<String, String> ASSETS = Map.of(
            "asset_replicator_spacedock", "temperature",
            "asset_litterbox_transporter_ds9", "vibration");

    public static final List<StockPlan> STOCK = stock();

    private static List<StockPlan> stock() {
        var plan = new java.util.ArrayList<StockPlan>();
        for (Product p : PRODUCTS) {
            plan.add(new StockPlan("site_spacedock", p.sku(), 500));
        }
        for (Product p : PRODUCTS) {
            plan.add(new StockPlan("site_utopia_planitia", p.sku(), 2000));
        }
        plan.add(new StockPlan("site_ds9", "CAT-TRIBBLE-TOY", 1));
        plan.add(new StockPlan("site_ds9", "CAT-COMBADGE-COLLAR", 20));
        plan.add(new StockPlan("site_ds9", "CAT-CAPTAINS-BED", 20));
        plan.add(new StockPlan("site_ds9", "CAT-DILITHIUM-NIP", 20));
        return List.copyOf(plan);
    }

    public static StockPlan plan(String siteId, String sku) {
        return STOCK.stream().filter(p -> p.siteId().equals(siteId) && p.sku().equals(sku)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a fixture stock record"));
    }

    public static boolean isFixtureStock(String siteId, String sku) {
        return STOCK.stream().anyMatch(p -> p.siteId().equals(siteId) && p.sku().equals(sku));
    }
}
