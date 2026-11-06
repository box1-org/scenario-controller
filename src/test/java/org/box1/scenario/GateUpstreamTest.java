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

/** A controller in an allowed namespace but aimed at hosts off the upstream allowlist refuses. Requires -Pcontainers. */
@QuarkusTest
@Tag("containers")
@TestProfile(GateUpstreamTest.OtherHosts.class)
@QuarkusTestResource(TestTokens.class)
@QuarkusTestResource(value = StackResource.class, restrictToAnnotatedClass = true)
class GateUpstreamTest {
    public static class OtherHosts implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("scenario.upstream-host-allowlist", "order-service.order-service-dev.svc");
        }
    }

    @Test
    void upstreamsOffTheAllowlistCloseTheGate() {
        as().body("{}").when().post("/internal/v1/scenarios/inventory-contention/start").then().statusCode(403)
                .body("detail", is("Upstream host 127.0.0.1 is not on the upstream allowlist"));
        as().when().post("/internal/v1/fixtures/seed").then().statusCode(403);
    }
}
