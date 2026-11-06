package org.box1.scenario.run;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.box1.scenario.StackResource;
import org.box1.scenario.TestTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A run left open by a process that died: at startup it is aborted and reset; if its TTL passes with no thread to end
 * it, the sweep ends and resets it. Nothing stays enabled beyond the TTL. Requires -Pcontainers.
 */
@QuarkusTest
@Tag("containers")
@QuarkusTestResource(TestTokens.class)
@QuarkusTestResource(value = StackResource.class, restrictToAnnotatedClass = true)
class AbandonedRunTest {
    @Inject
    Engine engine;

    @Inject
    DataSource dataSource;

    @Inject
    RunStore store;

    private void openRun(String runId, Instant started, Instant expires) throws Exception {
        String sql = "INSERT INTO scenario_run (run_id, scenario, title, mode, status, actor, environment, namespace, ttl_seconds,"
                + " correlation_id, started_at, expires_at) VALUES (?, 'inventory-contention', 'The Trouble with Tribbles',"
                + " 'SERVICE_API', 'RUNNING', 'dead-process', 'test', 'scenario-controller-dev', 60, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, runId);
            s.setString(2, "scenario-" + runId);
            s.setTimestamp(3, Timestamp.from(started));
            s.setTimestamp(4, Timestamp.from(expires));
            s.executeUpdate();
        }
    }

    @Test
    void aRunLeftByADeadProcessIsAbortedAndResetAtStartup() throws Exception {
        StackResource.fake.reset();
        given().header("Authorization", "Bearer " + TestTokens.operator("s")).post("/internal/v1/fixtures/seed").then().statusCode(200);
        StackResource.fake.stock.get("site_ds9|CAT-TRIBBLE-TOY")[1] = 1;
        String runId = "scn_00000000000000000001";
        openRun(runId, Instant.now(), Instant.now().plusSeconds(600));
        engine.recover();
        RunStore.Run run = store.find(runId).orElseThrow();
        assertEquals(RunState.ABORTED_RESTART, run.status());
        assertEquals("RESET_OK", run.resetResult());
        int[] tribble = StackResource.fake.stock.get("site_ds9|CAT-TRIBBLE-TOY");
        assertEquals(1, tribble[0] - tribble[1], "fixture stock restored");
        assertTrue(store.auditFor(runId).stream().anyMatch(a -> a.action().equals("RESULT") && a.outcome().equals("ABORTED_RESTART")));
    }

    @Test
    void aRunPastItsTtlWithNoThreadIsExpiredAndReset() throws Exception {
        String runId = "scn_00000000000000000002";
        openRun(runId, Instant.now().minusSeconds(200), Instant.now().minusSeconds(100));
        engine.sweep();
        RunStore.Run run = store.find(runId).orElseThrow();
        assertEquals(RunState.EXPIRED, run.status());
        assertEquals("TTL of 60 s passed", run.detail());
        assertEquals("RESET_OK", run.resetResult());
    }

    @Test
    void theAuditTrailIsAppendOnly() throws Exception {
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO scenario_audit (action, outcome, actor, environment, namespace, occurred_at)"
                    + " VALUES ('TEST', 'OK', 'a', 'test', 'n', now())");
            assertThrowsSql(() -> s.execute("UPDATE scenario_audit SET actor = 'b' WHERE action = 'TEST'"));
            assertThrowsSql(() -> s.execute("DELETE FROM scenario_audit WHERE action = 'TEST'"));
            assertThrowsSql(() -> s.execute("TRUNCATE scenario_audit CASCADE"));
            s.execute("INSERT INTO scenario_audit (action, outcome, actor, environment, namespace, occurred_at)"
                    + " VALUES ('OLD', 'OK', 'a', 'test', 'n', now() - interval '91 days')");
            assertEquals(1, s.executeUpdate("DELETE FROM scenario_audit WHERE action = 'OLD'"), "retention may delete old rows");
        }
    }

    private interface Sql {
        void run() throws Exception;
    }

    private static void assertThrowsSql(Sql action) {
        try {
            action.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("expected the statement to be refused");
    }
}
