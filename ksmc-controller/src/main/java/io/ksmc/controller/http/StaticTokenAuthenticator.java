package io.ksmc.controller.http;

import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * Authentification provisoire en attendant les comptes de l'API REST : un jeton administrateur unique
 * ({@code Authorization: Bearer <jeton>}) lu dans le Secret {@code ksmc-api-token}. Sans jeton configuré, tout est refusé.
 */
public final class StaticTokenAuthenticator implements ApiAuthenticator {

    private final byte[] token;

    public StaticTokenAuthenticator(String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<Principal> authenticate(Context ctx) {
        String header = ctx.header("Authorization");
        if (token == null || header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, given) ? Optional.of(new Principal("admin", Set.of("*"))) : Optional.empty();
    }
}
