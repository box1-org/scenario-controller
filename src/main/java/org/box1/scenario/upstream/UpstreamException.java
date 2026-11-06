package org.box1.scenario.upstream;

/** An upstream call failed or answered unexpectedly. The message names the service and status, never a body. */
public class UpstreamException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public UpstreamException(String message, int status) {
        super(message);
        this.status = status;
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }
}
