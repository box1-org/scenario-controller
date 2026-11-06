package org.box1.scenario.upstream;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;

/**
 * Client-credentials token for inventory admin calls (D-009). Cached until 30 s before expiry. The token is never
 * logged, stored or returned to a caller.
 */
@ApplicationScoped
public class InventoryToken {
    private final Http http;
    private final ScenarioSettings.InventoryToken settings;
    private final Clock clock;
    private String cached;
    private Instant expires = Instant.EPOCH;

    public InventoryToken(Http http, ScenarioSettings settings, Clock clock) {
        this.http = http;
        this.settings = settings.inventoryToken();
        this.clock = clock;
    }

    /** Thrown when no inventory admin identity is configured. */
    public static class NotConfigured extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotConfigured() {
            super("Inventory admin identity is not configured");
        }
    }

    public boolean configured() {
        return settings.configured();
    }

    synchronized String get() {
        if (!configured()) {
            throw new NotConfigured();
        }
        if (cached != null && clock.instant().isBefore(expires)) {
            return cached;
        }
        java.net.URI uri = java.net.URI.create(settings.url());
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !local) {
            throw new UpstreamException("Token URL must use https", 0);
        }
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(settings.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(settings.clientSecret(), StandardCharsets.UTF_8);
        int slash = settings.url().indexOf('/', settings.url().indexOf("//") + 2);
        String base = slash < 0 ? settings.url() : settings.url().substring(0, slash);
        String path = slash < 0 ? "/" : settings.url().substring(slash);
        Http.Reply reply = http.send("POST", base, path, form, Map.of("Content-Type", "application/x-www-form-urlencoded"), null);
        if (reply.status() != 200 || reply.body() == null || !reply.body().hasNonNull("access_token")) {
            throw new UpstreamException("Token endpoint answered " + reply.status(), reply.status());
        }
        cached = reply.body().get("access_token").asText();
        long seconds = reply.body().path("expires_in").asLong(60);
        expires = clock.instant().plusSeconds(Math.max(0, seconds - 30));
        return cached;
    }
}
