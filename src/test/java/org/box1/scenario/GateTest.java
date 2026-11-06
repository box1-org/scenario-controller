package org.box1.scenario;

import static org.box1.scenario.Api.as;
import static org.hamcrest.Matchers.is;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A controller in a namespace that is not on the allowlist fails closed for every action. Requires -Pcontainers. */
@QuarkusTest
@Tag("containers")
@TestProfile(GateTest.OtherNamespace.class)
@QuarkusTestResource(TestTokens.class)
@QuarkusTestResource(value = StackResource.class, restrictToAnnotatedClass = true)
class GateTest {
    public static class OtherNamespace implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("scenario.namespace", "payments-prod");
        }
    }

    @Test
    void everyActionIsRefusedAndAudited() {
        as().body("{}").when().post("/internal/v1/scenarios/inventory-contention/start").then().statusCode(403)
                .body("detail", is("Scenario inventory-contention is not allowed in namespace payments-prod"));
        as().when().post("/internal/v1/scenarios/inventory-contention/reset").then().statusCode(403);
        as().when().post("/internal/v1/scenarios/inventory-contention/stop").then().statusCode(403);
        as().when().post("/internal/v1/fixtures/seed").then().statusCode(403);
        as().when().get("/internal/v1/audit").then().statusCode(200).body("[0].outcome", is("DENIED"))
                .body("[0].namespace", is("payments-prod")).body("[0].action", is("SEED"));
        org.junit.jupiter.api.Assertions.assertTrue(StackResource.fake.writes.stream().noneMatch(w -> w.contains("/internal/")
                || w.contains("/api/v1/orders")), "nothing reached a service");
    }
}
