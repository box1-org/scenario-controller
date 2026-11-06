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

/** With control disabled and no namespace, everything is refused. Requires -Pcontainers. */
@QuarkusTest
@Tag("containers")
@TestProfile(GateClosedTest.Closed.class)
@QuarkusTestResource(TestTokens.class)
@QuarkusTestResource(value = StackResource.class, restrictToAnnotatedClass = true)
class GateClosedTest {
    public static class Closed implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("scenario.control-enabled", "false", "scenario.test-namespace", " ");
        }
    }

    @Test
    void disabledControlRefuses() {
        as().body("{}").when().post("/internal/v1/scenarios/inventory-contention/start").then().statusCode(403)
                .body("detail", is("Scenario control is disabled in namespace unknown"));
    }
}
