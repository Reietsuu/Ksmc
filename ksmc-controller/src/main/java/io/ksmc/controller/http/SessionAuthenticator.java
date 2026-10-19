package io.ksmc.controller.http;

import io.javalin.http.Context;
import io.ksmc.controller.auth.AccountStore;
import io.ksmc.controller.auth.Jwt;
import java.util.Optional;

/**
 * {@code Authorization: Bearer <jeton>} : jeton d'API ({@code ksmc_...}) ou JWT de session. Les permissions sont relues
 * dans le magasin de comptes à chaque requête (un rôle retiré ou un compte supprimé prend effet tout de suite).
 */
public final class SessionAuthenticator implements ApiAuthenticator {

    private final AccountStore accounts;
    private final Jwt jwt;

    public SessionAuthenticator(AccountStore accounts, Jwt jwt) {
        this.accounts = accounts;
        this.jwt = jwt;
    }

    @Override
    public Optional<Principal> authenticate(Context ctx) {
        Object cached = ctx.attribute(ApiSupport.PRINCIPAL);
        if (cached instanceof Principal p) {
            return Optional.of(p);
        }
        String header = ctx.header("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        if (token == null && ctx.path().startsWith("/api/v1/") && isWebSocketOrStream(ctx)) {
            // Navigateurs : pas d'en-tête possible pour WebSocket/EventSource.
            token = ctx.queryParam("access_token");
        }
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Optional<AccountStore.Account> account = token.startsWith("ksmc_") ? accounts.authenticateToken(token)
                : jwt.verify(token, "access").flatMap(c -> accounts.account((String) c.get("sub")));
        return account.map(a -> new Principal(a.name(), a.permissions(), a.mustChangePassword()));
    }

    private static boolean isWebSocketOrStream(Context ctx) {
        String upgrade = ctx.header("Upgrade");
        String accept = ctx.header("Accept");
        return "websocket".equalsIgnoreCase(upgrade) || (accept != null && accept.contains("text/event-stream"));
    }
}
