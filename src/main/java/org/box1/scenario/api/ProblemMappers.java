package org.box1.scenario.api;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.box1.scenario.run.RunStore;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

/** Maps failures to RFC 9457 responses. Parser, token and driver messages are not returned to callers. */
public class ProblemMappers {
    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

    @ServerExceptionMapper
    public Response problem(RequestProblem error) {
        return response(Problem.of(error.status(), error.title(), error.getMessage()));
    }

    @ServerExceptionMapper
    public Response store(RunStore.StoreException error) {
        LOG.error(error.getMessage());
        return response(Problem.of(503, "Service Unavailable", "The audit store is unavailable"));
    }

    @ServerExceptionMapper
    public Response unauthorized(UnauthorizedException error) {
        return unauthenticated();
    }

    @ServerExceptionMapper
    public Response authenticationFailed(AuthenticationFailedException error) {
        return unauthenticated();
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException error) {
        return response(Problem.of(403, "Forbidden", "This operation needs a scenario-controller role"));
    }

    @ServerExceptionMapper
    public Response framework(WebApplicationException error) {
        int status = error.getResponse().getStatus();
        String title = error.getResponse().getStatusInfo().getReasonPhrase();
        return response(Problem.of(status, title, status == 400 ? "Request body is not valid" : title));
    }

    @ServerExceptionMapper
    public Response unreadable(JsonProcessingException error) {
        return response(Problem.of(400, "Bad Request", "Request body is not valid"));
    }

    private static Response unauthenticated() {
        return Response.fromResponse(response(Problem.of(401, "Unauthorized", "A valid bearer token is required")))
                .header("WWW-Authenticate", "Bearer").build();
    }

    static Response response(Problem problem) {
        return Response.status(problem.status()).type(Problem.MEDIA_TYPE).entity(problem).build();
    }
}
