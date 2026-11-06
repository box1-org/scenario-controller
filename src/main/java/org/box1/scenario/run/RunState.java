package org.box1.scenario.run;

/** Run states (D-008). Only RUNNING and AWAITING_OPERATOR are open. */
public enum RunState {
    RUNNING, AWAITING_OPERATOR, VERIFIED, NOT_VERIFIED, FAILED, STOPPED, EXPIRED, ABORTED_RESTART;

    public boolean open() {
        return this == RUNNING || this == AWAITING_OPERATOR;
    }
}
