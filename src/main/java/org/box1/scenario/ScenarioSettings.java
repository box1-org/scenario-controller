package org.box1.scenario;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Controller configuration. Upstream URLs are fixed here; nothing a caller sends becomes a URL. */
@ConfigMapping(prefix = "scenario")
public interface ScenarioSettings {
    boolean controlEnabled();

    /** Test runs only: stands in for POD_NAMESPACE, which is otherwise read from the environment alone. */
    Optional<String> testNamespace();

    /** Hosts the upstream and token URLs may point at. Anything else closes the gate. */
    Optional<List<String>> upstreamHostAllowlist();

    Optional<List<String>> namespaceAllowlist();

    String environment();

    String orderUrl();

    String inventoryUrl();

    String adapterUrl();

    Duration upstreamTimeout();

    Duration pollInterval();

    /** Interval of the expiry sweep, read by the scheduler. */
    String expiryInterval();

    /** How long an acknowledged request must wait before shipping for a delay to count as visible. */
    @WithDefault("PT60S")
    Duration minimumVisibleDelay();

    /** How long to watch after a first shipment for a duplicate. */
    @WithDefault("PT10S")
    Duration shipmentSettle();

    List<String> allowedAzp();

    InventoryToken inventoryToken();

    Readiness readiness();

    /** Readiness switches. */
    interface Readiness {
        boolean database();
    }

    /** Client-credentials settings for inventory admin calls. "none" means not configured. */
    interface InventoryToken {
        @WithDefault("none")
        String url();

        @WithDefault("none")
        String clientId();

        @WithDefault("none")
        String clientSecret();

        default boolean configured() {
            return !"none".equals(url()) && !"none".equals(clientId()) && !"none".equals(clientSecret());
        }
    }
}
