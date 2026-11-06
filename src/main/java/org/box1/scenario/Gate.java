package org.box1.scenario;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The safety gates (D-006). All must hold, and anything unset fails closed:
 * <ol>
 * <li>control is enabled;</li>
 * <li>the controller's own namespace, read from the POD_NAMESPACE environment variable (downward API) and from no
 * other config source, is on the allowlist;</li>
 * <li>every upstream and token URL points at a host on the upstream allowlist, so the controller cannot be aimed at
 * another environment's services by configuration alone.</li>
 * </ol>
 * Messages are plain.
 */
@ApplicationScoped
public class Gate {
    private final ScenarioSettings settings;
    private final String namespace;

    public Gate(ScenarioSettings settings) {
        this.settings = settings;
        String fromEnv = System.getenv("POD_NAMESPACE");
        String value = LaunchMode.current() == LaunchMode.TEST ? settings.testNamespace().orElse(fromEnv) : fromEnv;
        this.namespace = value == null || value.isBlank() ? "unknown" : value;
    }

    /** The namespace the controller runs in, or "unknown". */
    public String namespace() {
        return namespace;
    }

    public String environment() {
        return settings.environment();
    }

    /** Hosts of the configured upstreams, for the audit record. */
    public List<String> targets() {
        List<String> hosts = new ArrayList<>();
        for (String url : urls()) {
            hosts.add(host(url));
        }
        return hosts;
    }

    /** Null when allowed; otherwise the refusal text. */
    public String refusal(String scenario) {
        if (!settings.controlEnabled()) {
            return "Scenario control is disabled in namespace " + namespace;
        }
        List<String> allowed = settings.namespaceAllowlist().orElse(List.of());
        if ("unknown".equals(namespace) || !allowed.contains(namespace)) {
            return "Scenario " + scenario + " is not allowed in namespace " + namespace;
        }
        List<String> hosts = settings.upstreamHostAllowlist().orElse(List.of());
        for (String url : urls()) {
            if ("none".equals(url)) {
                continue;
            }
            String host = host(url);
            if (host == null || !hosts.contains(host)) {
                return "Upstream host " + (host == null ? "unknown" : host) + " is not on the upstream allowlist";
            }
        }
        return null;
    }

    private List<String> urls() {
        return List.of(settings.orderUrl(), settings.inventoryUrl(), settings.adapterUrl(), settings.inventoryToken().url());
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
