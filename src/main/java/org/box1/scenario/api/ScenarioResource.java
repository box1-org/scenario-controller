package org.box1.scenario.api;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.box1.scenario.catalog.Catalog;
import org.box1.scenario.run.Engine;
import org.box1.scenario.run.RunStore;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/** The scenario catalog and run control. Every path needs a scenario-controller role; the backend enforces it. */
@Path("/internal/v1/scenarios")
@Produces(MediaType.APPLICATION_JSON)
@SecurityScheme(securitySchemeName = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearer")
public class ScenarioResource {
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,48}$");

    private final Catalog catalog;
    private final Engine engine;
    private final JsonWebToken token;

    public ScenarioResource(Catalog catalog, Engine engine, JsonWebToken token) {
        this.catalog = catalog;
        this.engine = engine;
        this.token = token;
    }

    @GET
    @RolesAllowed({ClientRoleAugmentor.VIEWER, ClientRoleAugmentor.OPERATOR})
    @Operation(operationId = "listScenarios", summary = "The scenario catalog, with display titles, mechanisms and availability.")
    @APIResponse(responseCode = "200", description = "Catalog", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.ScenarioView.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks a role", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public List<Views.ScenarioView> list() {
        return catalog.all().stream().map(Views.ScenarioView::of).toList();
    }

    @POST
    @Path("/{name}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(ClientRoleAugmentor.OPERATOR)
    @Operation(operationId = "startScenario", summary = "Start a named scenario. Bounded by its TTL, audited, one run at a time.")
    @APIResponse(responseCode = "202", description = "Run started", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.RunView.class)))
    @APIResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks the role, or the namespace is not allowed", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Unknown scenario", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "409", description = "Not yet possible, or another run is active", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public jakarta.ws.rs.core.Response start(@PathParam("name") String name, Views.Start body) {
        RunStore.Run run = engine.start(valid(name), body == null ? null : body.ttlSeconds(), actor());
        return jakarta.ws.rs.core.Response.status(202).entity(Views.RunView.of(run, null)).build();
    }

    @POST
    @Path("/{name}/stop")
    @RolesAllowed(ClientRoleAugmentor.OPERATOR)
    @Operation(operationId = "stopScenario", summary = "Stop the active run of a scenario, then reset its fixture state.")
    @APIResponse(responseCode = "200", description = "Run stopped and reset", content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = Views.RunView.class)))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks the role, or the namespace is not allowed", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Unknown scenario", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "409", description = "No active run of the scenario", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public Views.RunView stop(@PathParam("name") String name) {
        return Views.RunView.of(engine.stop(valid(name), actor()), null);
    }

    @POST
    @Path("/{name}/reset")
    @RolesAllowed(ClientRoleAugmentor.OPERATOR)
    @Operation(operationId = "resetScenario", summary = "Restore a scenario's fixture stock. Stops its active run first. Additive only.")
    @APIResponse(responseCode = "200", description = "Reset result", content = @Content(mediaType = MediaType.APPLICATION_JSON))
    @APIResponse(responseCode = "401", description = "No valid token", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "403", description = "Token lacks the role, or the namespace is not allowed", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    @APIResponse(responseCode = "404", description = "Unknown scenario", content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
    public Map<String, Object> reset(@PathParam("name") String name) {
        return engine.reset(valid(name), actor());
    }

    private String actor() {
        return Actors.of(token);
    }

    /** Names outside the catalog's pattern are refused before anything else, without echoing them. */
    static String valid(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new RequestProblem(404, "Not Found", "Scenario is not in the catalog");
        }
        return name;
    }
}
