package org.box1.scenario.api;

import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The audit actor: the token's subject (stable, a UUID in Keycloak), followed by the username for readers. The
 * username is mutable and display-only; the subject identifies.
 */
final class Actors {
    private static final int MAX = 160;

    private Actors() {}

    static String of(JsonWebToken token) {
        String subject = clean(token.getSubject());
        String username = clean(token.getClaim("preferred_username"));
        String actor = subject == null ? "unknown-subject" : subject;
        if (username != null) {
            actor = actor + " (" + username + ")";
        }
        return actor.length() > MAX ? actor.substring(0, MAX) : actor;
    }

    private static String clean(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().replaceAll("\\p{Cntrl}", "");
        return text.isBlank() ? null : text;
    }
}
