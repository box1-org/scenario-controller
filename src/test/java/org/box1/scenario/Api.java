package org.box1.scenario;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

/** Test calls to the controller as an operator. */
final class Api {
    static final String OPERATOR = "subject-operator";

    private Api() {}

    static RequestSpecification as() {
        return given().header("Authorization", "Bearer " + TestTokens.operator(OPERATOR)).contentType("application/json");
    }

    static String start(String scenario, Integer ttl) {
        return as().body(ttl == null ? "{}" : "{\"ttlSeconds\":" + ttl + "}").when().post("/internal/v1/scenarios/" + scenario + "/start")
                .then().statusCode(202).extract().path("scenarioRunId");
    }

    static JsonPath run(String runId) {
        return as().when().get("/internal/v1/runs/" + runId).then().statusCode(200).extract().jsonPath();
    }

    /** Poll the run until the predicate holds, or fail after the limit. */
    static JsonPath until(String runId, Duration limit, Predicate<JsonPath> condition) {
        Instant end = Instant.now().plus(limit);
        JsonPath last = null;
        while (Instant.now().isBefore(end)) {
            last = run(runId);
            if (condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }
        throw new AssertionError("Run " + runId + " did not reach the condition; last: " + (last == null ? "none" : last.prettify()));
    }

    static JsonPath ended(String runId) {
        return until(runId, Duration.ofSeconds(30), r -> r.getString("resetResult") != null);
    }
}
