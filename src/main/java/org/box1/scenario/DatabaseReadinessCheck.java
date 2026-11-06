package org.box1.scenario;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * The audit store is a readiness dependency: without it no run can be audited, so none may start. The services the
 * scenarios call are deliberately not: their absence is reported per run, not as an unready controller.
 */
@Readiness
@ApplicationScoped
public class DatabaseReadinessCheck implements HealthCheck {
    private static final int VALIDATION_SECONDS = 2;

    private final DataSource dataSource;
    private final boolean enabled;

    public DatabaseReadinessCheck(DataSource dataSource, @ConfigProperty(name = "scenario.readiness.database") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public HealthCheckResponse call() {
        if (!enabled) {
            return HealthCheckResponse.named("database").up().withData("check", "disabled").build();
        }
        try (Connection connection = dataSource.getConnection()) {
            return HealthCheckResponse.named("database").status(connection.isValid(VALIDATION_SECONDS)).build();
        } catch (SQLException error) {
            return HealthCheckResponse.named("database").down().withData("sqlState", String.valueOf(error.getSQLState())).build();
        }
    }
}
