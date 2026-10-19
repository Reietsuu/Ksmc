package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.controller.auth.AccountStore;
import io.ksmc.controller.auth.Jwt;
import io.ksmc.controller.auth.LoginRateLimiter;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.module.ModuleExtensions;
import io.ksmc.controller.template.Digests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sessions (login, renouvellement, changement de mot de passe), jetons d'API, comptes et rôles.
 * Accès : JWT de session (15 min, renouvelable 7 j) ou jeton d'API, en {@code Authorization: Bearer}.
 */
public final class AuthApi implements HttpRoutes {

    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final AccountStore accounts;
    private final Jwt jwt;
    private final LoginRateLimiter perAccount;
    private final LoginRateLimiter perAddress;
    private final ExtensionRegistry extensions;
    private final AuditLog audit;

    public AuthApi(AccountStore accounts, Jwt jwt, LoginRateLimiter perAccount, LoginRateLimiter perAddress,
            ExtensionRegistry extensions, AuditLog audit) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.perAccount = perAccount;
        this.perAddress = perAddress;
        this.extensions = extensions;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.post("/api/v1/auth/login", this::login);
        routes.post("/api/v1/auth/refresh", this::refresh);
        routes.post("/api/v1/auth/password", this::changePassword);
        routes.get("/api/v1/auth/me", this::me);
        routes.get("/api/v1/auth/tokens", this::listTokens);
        routes.post("/api/v1/auth/tokens", this::createToken);
        routes.delete("/api/v1/auth/tokens/{id}", this::revokeToken);
        routes.get("/api/v1/accounts", this::listAccounts);
        routes.post("/api/v1/accounts", this::createAccount);
        routes.put("/api/v1/accounts/{name}/roles", this::setRoles);
        routes.put("/api/v1/accounts/{name}/password", this::resetPassword);
        routes.delete("/api/v1/accounts/{name}", this::deleteAccount);
        routes.get("/api/v1/roles", this::listRoles);
        routes.put("/api/v1/roles/{name}", this::putRole);
        routes.delete("/api/v1/roles/{name}", this::deleteRole);
        routes.get("/api/v1/permissions", this::permissions);
    }

    // ---- Sessions ----------------------------------------------------------------------------------

    /** Empreinte du hachage du mot de passe : un changement de mot de passe invalide les jetons de renouvellement. */
    private String passwordFingerprint(String user) {
        AccountStore.User u = accounts.snapshot().users.get(user);
        return u == null ? "" : Digests.sha256(u.passwordHash).substring(0, 16);
    }

    private Map<String, Object> session(String user, boolean mustChangePassword) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", jwt.sign(user, "access", ACCESS_TTL, Map.of()));
        out.put("refreshToken", jwt.sign(user, "refresh", REFRESH_TTL, Map.of("pwd", passwordFingerprint(user))));
        out.put("tokenType", "Bearer");
        out.put("expiresIn", ACCESS_TTL.toSeconds());
        out.put("mustChangePassword", mustChangePassword);
        return out;
    }

    private void login(Context ctx) throws Exception {
        JsonNode body = ApiSupport.body(ctx);
        String user = ApiSupport.text(body, "username");
        String password = ApiSupport.text(body, "password");
        String address = "ip:" + ctx.ip();
        long wait = Math.max(perAccount.retryAfter(user), perAddress.retryAfter(address));
        if (wait > 0) {
            ctx.header("Retry-After", String.valueOf(wait));
            throw new ApiException(429, "TooManyRequests", "trop de tentatives, réessayez dans " + wait + " s",
                    Map.of("retryAfterSeconds", wait));
        }
        var account = accounts.authenticate(user, password);
        if (account.isEmpty()) {
            perAccount.failure(user);
            perAddress.failure(address);
            audit.record(user, "auth.login", user, "denied", ctx.ip());
            throw new ApiException(401, "InvalidCredentials", "identifiants invalides");
        }
        perAccount.success(user);
        audit.record(user, "auth.login", user, "ok", ctx.ip());
        ctx.json(session(user, account.get().mustChangePassword()));
    }

    private void refresh(Context ctx) throws Exception {
        String token = ApiSupport.text(ApiSupport.body(ctx), "refreshToken");
        Map<String, Object> claims = jwt.verify(token, "refresh")
                .orElseThrow(() -> new ApiException(401, "Unauthorized", "jeton de renouvellement invalide ou expiré"));
        String user = (String) claims.get("sub");
        var account = accounts.account(user)
                .orElseThrow(() -> new ApiException(401, "Unauthorized", "compte supprimé"));
        if (!passwordFingerprint(user).equals(claims.get("pwd"))) {
            throw new ApiException(401, "Unauthorized", "mot de passe changé depuis : reconnectez-vous");
        }
        ctx.json(session(user, account.mustChangePassword()));
    }

    private void changePassword(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.principal(ctx);
        JsonNode body = ApiSupport.body(ctx);
        String current = ApiSupport.text(body, "currentPassword");
        String next = ApiSupport.text(body, "newPassword");
        if (accounts.authenticate(p.name(), current).isEmpty()) {
            throw new ApiException(401, "InvalidCredentials", "mot de passe actuel incorrect");
        }
        if (current.equals(next)) {
            throw ApiException.badRequest("le nouveau mot de passe doit être différent");
        }
        accounts.setPassword(p.name(), next, false);
        audit.record(p.name(), "auth.password", p.name(), "ok", null);
        // Nouvelle session : les anciens jetons de renouvellement ne sont plus valables.
        ctx.json(session(p.name(), false));
    }

    private void me(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.principal(ctx);
        ctx.json(Map.of("name", p.name(), "permissions", new TreeSet<>(p.permissions()),
                "mustChangePassword", p.mustChangePassword()));
    }

    // ---- Jetons d'API ------------------------------------------------------------------------------

    private void listTokens(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.principal(ctx);
        boolean all = "true".equals(ctx.queryParam("all"));
        if (all) {
            ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        accounts.tokens(all ? null : p.name()).forEach((id, t) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", t.name);
            m.put("user", t.user);
            m.put("createdAt", t.createdAt);
            m.put("expiresAt", t.expiresAt);
            out.add(m);
        });
        ctx.json(out);
    }

    private void createToken(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.principal(ctx);
        JsonNode body = ApiSupport.body(ctx);
        String name = ApiSupport.text(body, "name");
        Instant expires = null;
        if (body.hasNonNull("expiresInDays")) {
            int days = body.get("expiresInDays").asInt();
            if (days <= 0) {
                throw ApiException.badRequest("expiresInDays doit être positif");
            }
            expires = Instant.now().plus(Duration.ofDays(days));
        }
        AccountStore.CreatedToken t = accounts.createToken(p.name(), name, expires);
        audit.record(p.name(), "token.create", t.id(), "ok", name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("token", t.value());
        out.put("expiresAt", expires == null ? null : expires.toString());
        out.put("warning", "ce jeton ne sera plus affiché");
        ctx.status(201).json(out);
    }

    private void revokeToken(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.principal(ctx);
        String id = ctx.pathParam("id");
        AccountStore.Token t = accounts.token(id).orElseThrow(() -> ApiException.notFound("jeton inconnu : " + id));
        if (!p.name().equals(t.user)) {
            ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        }
        accounts.revokeToken(id);
        audit.record(p.name(), "token.revoke", id, "ok", t.name);
        ctx.status(204);
    }

    // ---- Comptes et rôles (administration) ----------------------------------------------------------

    private void listAccounts(Context ctx) {
        ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        accounts.snapshot().users.forEach((name, u) -> out.add(Map.of("name", name, "roles", u.roles,
                "mustChangePassword", u.mustChangePassword, "createdAt", String.valueOf(u.createdAt))));
        ctx.json(out);
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            throw ApiException.badRequest("liste attendue : " + field);
        }
        List<String> out = new ArrayList<>();
        v.forEach(x -> out.add(x.asText()));
        return out;
    }

    private void createAccount(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        JsonNode body = ApiSupport.body(ctx);
        String name = ApiSupport.text(body, "name");
        try {
            accounts.createUser(name, ApiSupport.text(body, "password"), strings(body, "roles"),
                    !body.has("mustChangePassword") || body.get("mustChangePassword").asBoolean());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        audit.record(p.name(), "account.create", name, "ok", null);
        ctx.status(201).json(Map.of("name", name));
    }

    private void setRoles(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        String name = ctx.pathParam("name");
        accounts.setRoles(name, strings(ApiSupport.body(ctx), "roles"));
        audit.record(p.name(), "account.roles", name, "ok", null);
        ctx.status(204);
    }

    private void resetPassword(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        String name = ctx.pathParam("name");
        accounts.setPassword(name, ApiSupport.text(ApiSupport.body(ctx), "password"), true);
        audit.record(p.name(), "account.password", name, "ok", null);
        ctx.status(204);
    }

    private void deleteAccount(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        String name = ctx.pathParam("name");
        if (name.equals(p.name())) {
            throw ApiException.badRequest("impossible de supprimer son propre compte");
        }
        accounts.deleteUser(name);
        audit.record(p.name(), "account.delete", name, "ok", null);
        ctx.status(204);
    }

    private void listRoles(Context ctx) {
        ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        ctx.json(accounts.snapshot().roles);
    }

    private void putRole(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        String name = ctx.pathParam("name");
        accounts.putRole(name, strings(ApiSupport.body(ctx), "permissions"));
        audit.record(p.name(), "role.put", name, "ok", null);
        ctx.status(204);
    }

    private void deleteRole(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ACCOUNT_ADMIN);
        String name = ctx.pathParam("name");
        try {
            accounts.deleteRole(name);
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        audit.record(p.name(), "role.delete", name, "ok", null);
        ctx.status(204);
    }

    /** Permissions intégrées et celles des routes des modules actifs. */
    private void permissions(Context ctx) {
        ApiSupport.principal(ctx);
        TreeSet<String> modules = new TreeSet<>();
        for (ModuleExtensions.Route r : extensions.all(ModuleExtensions.Route.class)) {
            if (r.permission() != null) {
                modules.add(r.permission());
            }
        }
        ctx.json(Map.of("builtin", Permissions.BUILTIN, "modules", modules));
    }
}
