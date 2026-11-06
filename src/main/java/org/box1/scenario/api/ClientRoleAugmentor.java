package org.box1.scenario.api;

import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.box1.scenario.ScenarioSettings;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Roles come only from this client's own claim, and only for tokens issued to an allowed client (azp). Without this,
 * the JWT layer falls back to a top-level groups claim, and any client with the audience would be accepted.
 */
@ApplicationScoped
public class ClientRoleAugmentor implements SecurityIdentityAugmentor {
    static final String CLIENT = "scenario-controller";
    public static final String VIEWER = "scenario-viewer";
    public static final String OPERATOR = "scenario-operator";

    private final Set<String> allowedAzp;

    public ClientRoleAugmentor(ScenarioSettings settings) {
        this.allowedAzp = Set.copyOf(settings.allowedAzp());
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder().setPrincipal(token)
                .addCredentials(identity.getCredentials()).addAttributes(identity.getAttributes());
        String azp = token.getClaim("azp");
        if (azp != null && allowedAzp.contains(azp)) {
            builder.addRoles(roles(token));
        }
        return Uni.createFrom().item(builder.build());
    }

    private static Set<String> roles(JsonWebToken token) {
        JsonObject access = token.getClaim("resource_access");
        JsonObject client = access == null ? null : access.getJsonObject(CLIENT);
        JsonArray roles = client == null ? null : client.getJsonArray("roles");
        if (roles == null) {
            return Set.of();
        }
        return roles.getValuesAs(JsonString.class).stream().map(JsonString::getString).collect(Collectors.toSet());
    }
}
