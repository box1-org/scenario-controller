package org.box1.scenario.run;

/**
 * One available scenario's fixed steps. Implementations call only the typed service clients through the context;
 * there is no generic command, shell or URL.
 */
public interface ScenarioExecutor {
    String name();

    Result execute(RunContext context) throws InterruptedException;

    /**
     * How a run ended. {@code guidedReset} is the console step an operator should take to undo an ERP-side change the
     * controller cannot observe, or null when there is none.
     */
    record Result(RunState state, String detail, String guidedReset) {
        public static Result verified(String detail) {
            return new Result(RunState.VERIFIED, detail, null);
        }

        public static Result notVerified(String detail) {
            return new Result(RunState.NOT_VERIFIED, detail, null);
        }

        public static Result failed(String detail) {
            return new Result(RunState.FAILED, detail, null);
        }

        public Result withGuidedReset(String step) {
            return new Result(state, detail, step);
        }
    }
}
