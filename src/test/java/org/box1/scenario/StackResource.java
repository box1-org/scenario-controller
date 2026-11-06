package org.box1.scenario;

import java.util.Map;
import java.util.UUID;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL (matching the deployed major version) in a container named run19-tc-*, plus the in-JVM fake services.
 * Switches the controller to its production behavior: migrations, scheduler, readiness, both safety gates open for
 * the namespace scenario-controller-dev.
 */
public class StackResource implements QuarkusTestResourceLifecycleManager {
    static final String POSTGRES_IMAGE = "docker.io/library/postgres:15-alpine";
    static PostgreSQLContainer<?> postgres;
    public static FakeServices fake;

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        try {
            String name = "run19-tc-pg-" + UUID.randomUUID().toString().substring(0, 8);
            postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("scenario_controller").withUsername("scenario_controller")
                    .withCreateContainerCmdModifier(cmd -> cmd.withName(name));
            postgres.start();
            fake = new FakeServices();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return Map.ofEntries(
                Map.entry("quarkus.datasource.jdbc.url", postgres.getJdbcUrl()),
                Map.entry("quarkus.datasource.username", postgres.getUsername()),
                Map.entry("quarkus.datasource.password", postgres.getPassword()),
                Map.entry("quarkus.flyway.migrate-at-start", "true"),
                Map.entry("quarkus.scheduler.enabled", "true"),
                Map.entry("scenario.readiness.database", "true"),
                Map.entry("scenario.control-enabled", "true"),
                Map.entry("scenario.test-namespace", "scenario-controller-dev"),
                Map.entry("scenario.upstream-host-allowlist", "127.0.0.1"),
                Map.entry("scenario.shipment-settle", "PT1S"),
                Map.entry("scenario.namespace-allowlist", "scenario-controller-dev"),
                Map.entry("scenario.environment", "test"),
                Map.entry("scenario.order-url", fake.url()),
                Map.entry("scenario.inventory-url", fake.url()),
                Map.entry("scenario.adapter-url", fake.url()),
                Map.entry("scenario.inventory-token.url", fake.url() + "/token"),
                Map.entry("scenario.inventory-token.client-id", "scenario-controller"),
                Map.entry("scenario.inventory-token.client-secret", "test-only-not-a-secret"),
                Map.entry("scenario.poll-interval", "PT0.1S"),
                Map.entry("scenario.expiry-interval", "1s"),
                Map.entry("scenario.minimum-visible-delay", "PT1S"));
    }

    @Override
    public void stop() {
        if (fake != null) {
            fake.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }
}
