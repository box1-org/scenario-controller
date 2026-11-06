package org.box1.scenario;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The two safety gates (D-006): control must be enabled, and the controller's own namespace (downward API) must be on
 * the allowlist. Anything unset fails closed. Messages are plain.
 */
@ApplicationScoped
public class Gate {
    private final ScenarioSettings settings;

    public Gate(ScenarioSettings settings) {
        this.settings = settings;
    }

    /** The namespace the controller runs in, or "unknown". */
    public String namespace() {
        return settings.namespace().filter(n -> !n.isBlank()).orElse("unknown");
    }

    public String environment() {
        return settings.environment();
    }

    /** Null when allowed; otherwise the refusal text. */
    public String refusal(String scenario) {
        if (!settings.controlEnabled()) {
            return "Scenario control is disabled in namespace " + namespace();
        }
        List<String> allowed = settings.namespaceAllowlist().orElse(List.of());
        String namespace = namespace();
        if ("unknown".equals(namespace) || !allowed.contains(namespace)) {
            return "Scenario " + scenario + " is not allowed in namespace " + namespace;
        }
        return null;
    }
}
