package org.box1.scenario.catalog;

import java.util.List;

/** One catalog entry. Plain fields; whimsy lives only in title and description. */
public record Scenario(String name, String title, String description, Mechanism mechanism, Availability availability,
        String notPossibleReason, List<String> prerequisites, List<String> fixtures, int defaultDurationSeconds,
        int maxDurationSeconds, String expectedObservation, String resetAction, List<String> operatorSteps) {

    public enum Mechanism { SERVICE_API, GUIDED_ERP, VM_LEVEL, PLATFORM }

    public enum Availability { AVAILABLE, NOT_YET_POSSIBLE }

    public Scenario {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
        operatorSteps = operatorSteps == null ? List.of() : List.copyOf(operatorSteps);
    }

    public boolean available() {
        return availability == Availability.AVAILABLE;
    }
}
