package org.box1.scenario.upstream;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.scenario.ScenarioSettings;

/** Fulfillment adapter: status reads (read policy permit) and its held gauge. No writes. */
@ApplicationScoped
public class AdapterApi {
    private static final Pattern HELD = Pattern.compile("(?m)^fulfillment_held(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)$");

    private final Http http;
    private final String base;

    public AdapterApi(Http http, ScenarioSettings settings) {
        this.http = http;
        this.base = settings.adapterUrl();
    }

    public record Fulfillment(String fulfillmentRequestId, String orderId, String dispatch, String erpAcknowledgement,
            String outcome, String shipmentId, java.time.Instant acknowledgedAt, java.time.Instant statusAt) {}

    /** Empty when the adapter has no such request yet. */
    public Optional<Fulfillment> fulfillment(String fulfillmentRequestId, String correlationId) {
        Http.Reply reply = http.send("GET", base, "/api/fulfillments/" + Http.segment(fulfillmentRequestId), null, Map.of(),
                correlationId);
        if (reply.status() == 404) {
            return Optional.empty();
        }
        if (reply.status() != 200 || reply.body() == null) {
            throw new UpstreamException("Fulfillment adapter answered " + reply.status() + " to a status read", reply.status());
        }
        var b = reply.body();
        return Optional.of(new Fulfillment(fulfillmentRequestId, OrderApi.text(b, "orderId"), OrderApi.text(b, "dispatch"),
                OrderApi.text(b, "erpAcknowledgement"), OrderApi.text(b, "outcome"), OrderApi.text(b, "shipmentId"),
                instant(OrderApi.text(b, "acknowledgedAt")), instant(OrderApi.text(b, "statusAt"))));
    }

    private static java.time.Instant instant(String value) {
        try {
            return value == null ? null : java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException error) {
            return null;
        }
    }

    /** The adapter's fulfillment_held gauge, the metric signal for held work (no alert rule exists on this platform). */
    public double heldGauge() {
        Matcher m = HELD.matcher(http.text(base, "/q/metrics"));
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }
}
