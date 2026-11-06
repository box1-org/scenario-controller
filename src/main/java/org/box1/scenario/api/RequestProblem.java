package org.box1.scenario.api;

/** A request failure that maps directly to a Problem response. Detail text is plain and safe to return. */
public class RequestProblem extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String title;

    public RequestProblem(int status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
