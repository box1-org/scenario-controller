package org.box1.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.box1.scenario.fixtures.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * Checks the controller's assumptions against box1-ops-contracts at the commit pinned in pom.xml. The contracts are
 * fetched, never copied. Runs in CI; a fetch failure fails it rather than skipping.
 */
class ContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Order states the scenarios wait for or treat as dead ends. */
    private static final Set<String> STATES_USED = Set.of("SUBMITTED", "RESERVED", "FULFILLMENT_REQUESTED", "SHIPPED",
            "COMPLETED", "REJECTED", "EXCEPTION", "CANCELLED");

    private static String fetch(String path) throws IOException, InterruptedException {
        String ref = System.getProperty("contracts.ref");
        URI uri = URI.create("https://raw.githubusercontent.com/box1-org/box1-ops-contracts/" + ref + "/" + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            return response.body();
        }
    }

    @Test
    void everyFixtureOrderMatchesTheCreateOrderSchema() throws Exception {
        JsonNode schema = new YAMLMapper().readTree(fetch("openapi/order-service.yaml")).at("/components/schemas/CreateOrder");
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        for (Fixtures.Customer customer : Fixtures.CUSTOMERS) {
            for (Fixtures.StockPlan plan : Fixtures.STOCK) {
                JsonNode body = JSON.valueToTree(Map.of("customerId", customer.customerId(), "sku", plan.sku(), "quantity", 1,
                        "siteId", plan.siteId()));
                Set<ValidationMessage> errors = validator.validate(body);
                assertTrue(errors.isEmpty(), body + " " + errors);
            }
        }
    }

    @Test
    void theOrderStatesTheScenariosUseExist() throws Exception {
        String table = fetch("ORDER-STATE.md");
        JsonNode states = new YAMLMapper().readTree(fetch("openapi/order-service.yaml")).at("/components/schemas/OrderState/enum");
        List<String> declared = JSON.convertValue(states, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        assertTrue(declared.containsAll(STATES_USED), declared.toString());
        assertTrue(table.contains("`SUBMITTED` | `inventory.rejected.v1` | `REJECTED`"), "a rejection ends REJECTED");
        assertTrue(table.contains("`SHIPPED`, then `COMPLETED` if the completion policy allows"), "shipping may stop at SHIPPED");
    }

    @Test
    void theInventoryRoutesTheControllerReadsExist() throws Exception {
        JsonNode api = new YAMLMapper().readTree(fetch("openapi/inventory-service.yaml"));
        assertTrue(api.at("/paths/~1api~1v1~1inventory/get").isObject());
        assertTrue(api.at("/paths/~1internal~1v1~1stock~1{siteId}~1{sku}~1restock/post").isObject());
        JsonNode level = api.at("/components/schemas/StockLevel/properties");
        for (String field : List.of("siteId", "sku", "onHand", "reserved", "available")) {
            assertTrue(level.has(field), field);
        }
    }
}
