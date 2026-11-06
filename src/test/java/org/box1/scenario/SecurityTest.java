package org.box1.scenario;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import java.util.List;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Access rules that hold without a database. Runs in CI. Every role check is in the backend. */
@QuarkusTest
@QuarkusTestResource(TestTokens.class)
class SecurityTest {
    private static final List<String> READS = List.of("/internal/v1/scenarios", "/internal/v1/runs",
            "/internal/v1/runs/scn_00000000000000000000", "/internal/v1/audit", "/internal/v1/fixtures");
    private static final List<String> WRITES = List.of("/internal/v1/scenarios/baseline-order/start",
            "/internal/v1/scenarios/baseline-order/stop", "/internal/v1/scenarios/baseline-order/reset",
            "/internal/v1/fixtures/seed");

    private static void get(int status, String token, String path) {
        var request = given();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.when().get(path).then().statusCode(status).contentType(startsWith("application/problem+json"));
    }

    private static void post(int status, String token, String path) {
        var request = given().contentType("application/json").body("{}");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.when().post(path).then().statusCode(status).contentType(startsWith("application/problem+json"));
    }

    @Test
    void anonymousRequestsAreRejectedEverywhere() {
        READS.forEach(p -> get(401, null, p));
        WRITES.forEach(p -> post(401, null, p));
    }

    @Test
    void badTokensAreRejected() {
        for (String token : new String[] {"not-a-token", TestTokens.expired(), TestTokens.wrongIssuer(),
                TestTokens.wrongAudience(), TestTokens.wrongKey()}) {
            get(401, token, "/internal/v1/scenarios");
            post(401, token, "/internal/v1/scenarios/baseline-order/start");
        }
    }

    @Test
    void aTokenWithoutTheRoleIsForbidden() {
        READS.forEach(p -> get(403, TestTokens.noRole(), p));
        WRITES.forEach(p -> post(403, TestTokens.noRole(), p));
        WRITES.forEach(p -> post(403, TestTokens.viewer(), p));
    }

    @Test
    void rolesFromAnotherClientOrTheGroupsClaimAreIgnored() {
        get(403, TestTokens.otherClient(), "/internal/v1/scenarios");
        post(403, TestTokens.otherClient(), "/internal/v1/scenarios/baseline-order/start");
        get(403, TestTokens.groupsOnly(), "/internal/v1/scenarios");
        post(403, TestTokens.groupsOnly(), "/internal/v1/scenarios/baseline-order/start");
    }

    @Test
    void aViewerReadsTheCatalogWithTitles() {
        given().header("Authorization", "Bearer " + TestTokens.viewer()).when().get("/internal/v1/scenarios").then()
                .statusCode(200).body("size()", is(10))
                .body("title", hasItems("Make it so", "The Trouble with Tribbles", "Hold that order, Number One"))
                .body("find { it.name == 'red-alert' }.availability", is("NOT_YET_POSSIBLE"))
                .body("find { it.name == 'baseline-order' }.description", startsWith("Picard orders"));
    }

    @Test
    void smokePathsStayAnonymous() {
        given().when().get("/api/greetings").then().statusCode(200);
        given().when().get("/api/info").then().statusCode(200);
        given().when().get("/q/health/live").then().statusCode(200);
        given().when().get("/q/openapi").then().statusCode(200);
        given().when().get("/q/metrics").then().statusCode(200);
    }

    @Test
    void unknownAndMalformedNamesGetAPlain404() {
        String operator = TestTokens.operator("subject-1");
        given().header("Authorization", "Bearer " + operator).contentType("application/json").body("{}")
                .when().post("/internal/v1/scenarios/Not_A..Name/start").then().statusCode(404)
                .body("detail", is("Scenario is not in the catalog"));
    }
}
