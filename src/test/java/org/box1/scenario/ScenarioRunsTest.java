package org.box1.scenario;

import static org.box1.scenario.Api.as;
import static org.box1.scenario.Api.ended;
import static org.box1.scenario.Api.start;
import static org.box1.scenario.Api.until;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scenario behavior against real PostgreSQL and the fake services: the prompt's verification scenarios that need no
 * real upstream. Requires -Pcontainers.
 */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(TestTokens.class)
@QuarkusTestResource(value = StackResource.class, restrictToAnnotatedClass = true)
class ScenarioRunsTest {
    private FakeServices fake;

    @BeforeEach
    void freshFixtures() {
        fake = StackResource.fake;
        fake.reset();
        as().when().post("/internal/v1/fixtures/seed").then().statusCode(200);
    }

    /** Every audit record names the actor, environment, namespace, mode, TTL, impact and reset result. */
    private static void complete(JsonPath run) {
        for (String field : List.of("scenarioRunId", "scenario", "title", "mode", "status", "actor", "environment", "namespace",
                "correlationId", "startedAt", "startedStardate", "expiresAt", "endedAt", "resetResult", "resetAt")) {
            assertNotNull(run.get(field), field);
        }
        assertTrue(run.getInt("ttlSeconds") > 0);
        assertEquals("scenario-controller-dev", run.getString("namespace"));
        assertEquals("test", run.getString("environment"));
        assertEquals("scenario-" + run.getString("scenarioRunId"), run.getString("correlationId"));
        List<String> actions = run.getList("audit.action");
        assertTrue(actions.containsAll(List.of("START", "RESULT", "RESET")), actions.toString());
        assertTrue(run.getList("audit.stardate").stream().allMatch(s -> s.toString().matches("^-?[0-9]+\\.[0-9]$")));
    }

    @Test
    void seedingTwiceGivesTheSameStateAndTouchesOnlyFixtures() {
        fake.reset();
        Map<String, int[]> labSeed = fake.labSeedSnapshot();
        as().when().post("/internal/v1/fixtures/seed").then().statusCode(200).body("created", is(26)).body("unchanged", is(0));
        String first = fake.stock.keySet().toString() + fake.sites;
        as().when().post("/internal/v1/fixtures/seed").then().statusCode(200).body("created", is(0)).body("unchanged", is(26));
        assertEquals(first, fake.stock.keySet().toString() + fake.sites);
        assertEquals(1, fake.stock.get("site_ds9|CAT-TRIBBLE-TOY")[0]);
        assertEquals("UNAVAILABLE", fake.sites.get("site_neutral_zone"));
        Map<String, int[]> after = fake.labSeedSnapshot();
        for (var entry : labSeed.entrySet()) {
            assertArrayEquals(entry.getValue(), after.get(entry.getKey()), entry.getKey());
        }
        assertTrue(fake.writes.stream().allMatch(w -> w.equals("POST /token")
                || w.matches("PUT /internal/v1/(sites/site_(spacedock|ds9|utopia_planitia|neutral_zone)|stock/site_(spacedock|ds9|utopia_planitia)/CAT-[A-Z-]+)")),
                fake.writes.toString());
        as().when().get("/internal/v1/audit").then().statusCode(200).body("[0].action", is("SEED")).body("[0].actor", is(Api.OPERATOR));
    }

    @Test
    void makeItSoShipsAfterTheGuidedStepAndReplaysTheSameOrder() {
        String runId = start("baseline-order", null);
        JsonPath waiting = until(runId, Duration.ofSeconds(10), r -> "AWAITING_OPERATOR".equals(r.getString("status")));
        assertTrue(waiting.getString("operatorStep").contains("ful_"), waiting.getString("operatorStep"));
        fake.auto();
        JsonPath run = ended(runId);
        assertEquals("VERIFIED", run.getString("status"), run.prettify());
        assertEquals("RESET_OK", run.getString("resetResult"));
        assertEquals("Make it so", run.getString("title"));
        complete(run);
        List<String> orders = run.getList("impact.orders");
        assertEquals(1, orders.size());
        assertEquals(1, fake.orders.size(), "the replay of the run-scoped key created nothing");
        assertEquals(List.of("SUBMITTED", "RESERVED", "FULFILLMENT_REQUESTED", "SHIPPED", "COMPLETED"),
                fake.orders.get(orders.get(0)).transitions);
        assertEquals(500, fake.stock.get("site_spacedock|CAT-SPOT-BOWL")[0] - fake.stock.get("site_spacedock|CAT-SPOT-BOWL")[1],
                "reset restored the available stock");
    }

    @Test
    void theTroubleWithTribblesHasExactlyOneWinnerAndCanBeRepeated() {
        for (int round = 0; round < 2; round++) {
            int before = fake.orders.size();
            String runId = start("inventory-contention", null);
            JsonPath run = ended(runId);
            assertEquals("VERIFIED", run.getString("status"), run.prettify());
            complete(run);
            List<FakeServices.Order> placed = fake.orders.values().stream().skip(before).toList();
            assertEquals(2, placed.size());
            assertEquals(1, placed.stream().filter(o -> "FULFILLMENT_REQUESTED".equals(o.state)).count());
            assertEquals(1, placed.stream().filter(o -> "REJECTED".equals(o.state) && "INSUFFICIENT_STOCK".equals(o.statusReason)).count());
            int[] tribble = fake.stock.get("site_ds9|CAT-TRIBBLE-TOY");
            assertEquals(1, tribble[0] - tribble[1], "reset restored one available tribble, additively");
        }
    }

    @Test
    void aGuidedStepNotPerformedTimesOutAsNotVerified() {
        String runId = start("erp-manual-hold", 3);
        JsonPath run = ended(runId);
        assertEquals("NOT_VERIFIED", run.getString("status"));
        assertTrue(run.getString("detail").startsWith("Operator step not observed before the TTL"), run.getString("detail"));
        assertTrue(run.getList("audit.action").contains("OPERATOR_STEP"));
        complete(run);
    }

    @Test
    void holdThatOrderNumberOneIsVerifiedThroughTheAdapter() {
        String runId = start("erp-manual-hold", null);
        JsonPath waiting = until(runId, Duration.ofSeconds(10), r -> r.getString("operatorStep") != null
                && r.getString("operatorStep").contains("hold it"));
        String ful = waiting.getList("impact.fulfillmentRequests").get(0).toString();
        fake.hold(ful);
        until(runId, Duration.ofSeconds(10), r -> r.getString("operatorStep") != null && r.getString("operatorStep").contains("release"));
        fake.release(ful);
        JsonPath run = ended(runId);
        assertEquals("VERIFIED", run.getString("status"), run.prettify());
        assertTrue(run.getString("detail").contains("stayed FULFILLMENT_REQUESTED while held"));
        assertTrue(run.getString("detail").contains("fulfillment_held was 1.0"));
        assertEquals("RESET_OK", run.getString("resetResult"));
        complete(run);
    }

    @Test
    void impulsePowerOnlyNeedsAVisibleDelay() {
        fake.auto();
        String quick = start("erp-delayed-processing", 20);
        JsonPath noDelay = ended(quick);
        assertEquals("NOT_VERIFIED", noDelay.getString("status"));
        assertTrue(noDelay.getString("detail").contains("no visible delay"), noDelay.getString("detail"));
        assertEquals("RESET_GUIDED", noDelay.getString("resetResult"));

        fake.delay(2500);
        String delayed = start("erp-delayed-processing", 20);
        JsonPath run = ended(delayed);
        assertEquals("VERIFIED", run.getString("status"), run.prettify());
        assertEquals("RESET_GUIDED", run.getString("resetResult"));
        assertTrue(run.getString("resetDetail").contains("Set the ERP delay back to 0"));
        complete(run);
    }

    @Test
    void warpCoreBreachVerifiesOneShipment() {
        fake.auto();
        JsonPath run = ended(start("erp-crash-after-commit", 20));
        assertEquals("VERIFIED", run.getString("status"), run.prettify());
        assertTrue(run.getString("detail").contains("shipped exactly once"));
        complete(run);
    }

    @Test
    void stopEndsTheRunAndResets() {
        String runId = start("baseline-order", null);
        until(runId, Duration.ofSeconds(10), r -> "AWAITING_OPERATOR".equals(r.getString("status")));
        as().when().post("/internal/v1/scenarios/baseline-order/stop").then().statusCode(200).body("status", is("STOPPED"));
        JsonPath run = ended(runId);
        assertEquals("STOPPED", run.getString("status"));
        assertEquals("RESET_OK", run.getString("resetResult"));
        assertTrue(run.getList("audit.action").contains("STOP"));
        complete(run);
        as().when().post("/internal/v1/scenarios/baseline-order/stop").then().statusCode(409)
                .body("detail", is("No active run of scenario baseline-order"));
        String next = start("inventory-contention", null);
        assertEquals("VERIFIED", ended(next).getString("status"));
    }

    @Test
    void oneRunAtATime() {
        String runId = start("baseline-order", null);
        as().body("{}").when().post("/internal/v1/scenarios/inventory-contention/start").then().statusCode(409)
                .body("detail", is("Another scenario run is active: " + runId));
        as().when().post("/internal/v1/scenarios/baseline-order/reset").then().statusCode(200).body("scenarioRunId", is(runId));
        assertEquals("STOPPED", ended(runId).getString("status"));
    }

    @Test
    void resetWithoutARunRestoresFixtureStockOnly() {
        fake.stock.get("site_ds9|CAT-TRIBBLE-TOY")[1] = 1;
        as().when().post("/internal/v1/scenarios/inventory-contention/reset").then().statusCode(200)
                .body("resetResult", is("RESET_OK")).body("resetDetail", containsString("site_ds9"));
        int[] tribble = fake.stock.get("site_ds9|CAT-TRIBBLE-TOY");
        assertEquals(1, tribble[0] - tribble[1]);
        assertEquals(2, tribble[0], "restored by adding, never by releasing or reducing");
    }

    @Test
    void notYetPossibleAndUnknownScenariosAreRefusedAndAudited() {
        as().body("{}").when().post("/internal/v1/scenarios/red-alert/start").then().statusCode(409)
                .body("detail", containsString("Scenario red-alert is not yet possible: "));
        as().when().get("/internal/v1/audit").then().body("[0].action", is("START")).body("[0].outcome", is("DENIED"))
                .body("[0].scenario", is("red-alert"));
        as().body("{}").when().post("/internal/v1/scenarios/warp-ten/start").then().statusCode(404)
                .body("detail", is("Scenario is not in the catalog"));
        as().when().get("/internal/v1/audit").then().body("[0].outcome", is("DENIED")).body("[0].detail", is("Unknown scenario"));
        as().body("{\"ttlSeconds\":99999}").when().post("/internal/v1/scenarios/baseline-order/start").then().statusCode(400);
        as().body("{\"ttlSeconds\":1,\"command\":\"rm\"}").when().post("/internal/v1/scenarios/baseline-order/start").then().statusCode(400);
    }

    @Test
    void anUnseededFixtureFailsPlainly() {
        fake.reset();
        JsonPath run = ended(start("inventory-contention", null));
        assertEquals("FAILED", run.getString("status"));
        assertEquals("Fixtures are not seeded: no stock record site_ds9/CAT-TRIBBLE-TOY", run.getString("detail"));
    }
}
