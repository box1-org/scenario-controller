package org.box1.scenario.api;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.box1.scenario.fixtures.FixtureService;
import org.box1.scenario.fixtures.Fixtures;
import org.box1.scenario.run.Engine;
import org.box1.scenario.run.RunStore;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

/** Runs, the audit trail and the fixture set. */
@Path("/internal/v1")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearer")
public class RunResource {
    private static final Pattern RUN_ID = Pattern.compile("^scn_[0-9a-f]{20}$");

    private final RunStore store;
    private final Engine engine;
    private final JsonWebToken token;

    public RunResource(RunStore store, Engine engine, JsonWebToken token) {
        this.store = store;
        this.engine = engine;
        this.token = token;
    }

    @GET
    @Path("/runs")
    @RolesAllowed({ClientRoleAugmentor.VIEWER, ClientRoleAugmentor.OPERATOR})
    @Operation(operationId = "listRuns", summary = "Recent runs, newest first.")
    @APIResponse(responseCode = "200", description = "Runs", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.RunView.class)))
    @APIResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks a role", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public List<Views.RunView> runs(@QueryParam("limit") Integer limit) {
        return store.recent(bounded(limit)).stream().map(r -> Views.RunView.of(r, null)).toList();
    }

    @GET
    @Path("/runs/{scenarioRunId}")
    @RolesAllowed({ClientRoleAugmentor.VIEWER, ClientRoleAugmentor.OPERATOR})
    @Operation(operationId = "getRun", summary = "One run with its complete audit trail.")
    @APIResponse(responseCode = "200", description = "Run", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.RunView.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks a role", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "No such run", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public Views.RunView run(@PathParam("scenarioRunId") String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new RequestProblem(404, "Not Found", "No such run");
        }
        RunStore.Run run = store.find(runId).orElseThrow(() -> new RequestProblem(404, "Not Found", "No such run"));
        return Views.RunView.of(run, store.auditFor(runId));
    }

    @GET
    @Path("/audit")
    @RolesAllowed({ClientRoleAugmentor.VIEWER, ClientRoleAugmentor.OPERATOR})
    @Operation(operationId = "listAudit", summary = "The audit trail, newest first, including refusals.")
    @APIResponse(responseCode = "200", description = "Audit entries", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.AuditView.class)))
    @APIResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks a role", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public List<Views.AuditView> audit(@QueryParam("before") Long before, @QueryParam("limit") Integer limit) {
        if (before != null && before < 1) {
            throw new RequestProblem(400, "Bad Request", "before must be positive");
        }
        return store.auditPage(before, bounded(limit)).stream().map(Views.AuditView::of).toList();
    }

    @GET
    @Path("/fixtures")
    @RolesAllowed({ClientRoleAugmentor.VIEWER, ClientRoleAugmentor.OPERATOR})
    @Operation(operationId = "listFixtures", summary = "The Starfleet Feline Supply fixture set: IDs, display names and opening stock.")
    @APIResponse(responseCode = "200", description = "Fixtures", content = @Content(mediaType = MediaType.APPLICATION_JSON))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks a role", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public java.util.Map<String, Object> fixtures() {
        return java.util.Map.of("sites", Fixtures.SITES, "customers", Fixtures.CUSTOMERS, "products", Fixtures.PRODUCTS,
                "stock", Fixtures.STOCK, "assets", Fixtures.ASSETS);
    }

    @POST
    @Path("/fixtures/seed")
    @RolesAllowed(ClientRoleAugmentor.OPERATOR)
    @Operation(operationId = "seedFixtures", summary = "Create the fixture sites and stock records through inventory's API. Idempotent; never changes existing data.")
    @APIResponse(responseCode = "200", description = "Seed result", content = @Content(mediaType = MediaType.APPLICATION_JSON))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks the role, or the namespace is not allowed", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "502", description = "Inventory refused or is unavailable", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public FixtureService.SeedResult seed() {
        String subject = token.getName();
        return engine.seed(subject == null ? token.getSubject() : subject);
    }

    private static int bounded(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 200) {
            throw new RequestProblem(400, "Bad Request", "limit must be 1 to 200");
        }
        return limit;
    }
}
