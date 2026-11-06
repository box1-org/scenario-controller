package org.box1.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.jwt.build.Jwt;

/**
 * Issues tokens from a key generated for this test run only. The service verifies them with the same issuer, audience,
 * azp and role rules it uses in production; only the key location differs.
 */
public final class TestTokens implements QuarkusTestResourceLifecycleManager {
    static final String ISSUER = "https://keycloak.apps.ocp.box1.org/auth/realms/box1";
    static final String CLIENT = "scenario-controller";
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Map<String, String> start() {
        try {
            Path pem = Files.createTempFile("scenario-test-jwt", ".pem");
            String body = Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            return Map.of("mp.jwt.verify.publickey.location", pem.toUri().toString());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void stop() {
        // The key file holds only a public key.
    }

    public static String operator(String subject) {
        return token(subject, ISSUER, CLIENT, CLIENT, List.of("scenario-operator"), KEYS);
    }

    static String viewer() {
        return token("subject-viewer", ISSUER, CLIENT, CLIENT, List.of("scenario-viewer"), KEYS);
    }

    static String noRole() {
        return token("subject-norole", ISSUER, CLIENT, CLIENT, List.of(), KEYS);
    }

    static String otherClient() {
        return token("subject-azp", ISSUER, CLIENT, "operations-console", List.of("scenario-operator"), KEYS);
    }

    static String wrongAudience() {
        return token("subject-audience", ISSUER, "other-service", CLIENT, List.of("scenario-operator"), KEYS);
    }

    static String wrongIssuer() {
        return token("subject-issuer", "https://example.invalid/realms/box1", CLIENT, CLIENT, List.of("scenario-operator"), KEYS);
    }

    static String wrongKey() {
        return token("subject-key", ISSUER, CLIENT, CLIENT, List.of("scenario-operator"), OTHER_KEYS);
    }

    /** Signed correctly, with the role only in a top-level groups claim. */
    static String groupsOnly() {
        return Jwt.issuer(ISSUER).audience(CLIENT).subject("subject-groups").claim("azp", CLIENT)
                .groups(java.util.Set.of("scenario-operator")).sign(KEYS.getPrivate());
    }

    static String expired() {
        return Jwt.issuer(ISSUER).audience(CLIENT).subject("subject-expired").expiresAt(1000L).claim("azp", CLIENT)
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", List.of("scenario-operator"))))
                .sign(KEYS.getPrivate());
    }

    private static String token(String subject, String issuer, String audience, String azp, List<String> roles, KeyPair keys) {
        return Jwt.issuer(issuer).audience(audience).subject(subject).claim("azp", azp)
                .claim("resource_access", Map.of(CLIENT, Map.of("roles", roles)))
                .sign(keys.getPrivate());
    }
}
