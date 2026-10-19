package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socle de l'API publique : filtre d'authentification de {@code /api/v1/*}, erreurs JSON uniformes et utilitaires
 * (utilisateur courant, contrôle de permission, lecture du corps).
 */
public final class ApiSupport implements HttpRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(ApiSupport.class);
    static final String PRINCIPAL = "ksmc.principal";
    static final ObjectMapper JSON = new ObjectMapper();
    /** Chemins accessibles sans authentification. */
    static final Set<String> PUBLIC = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/openapi.json");
    /** Chemins accessibles avec un mot de passe à changer. */
    static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("/api/v1/auth/password", "/api/v1/auth/me");

    private final ApiAuthenticator auth;

    public ApiSupport(ApiAuthenticator auth) {
        this.auth = auth;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.before("/api/v1/*", this::authenticate);
        routes.exception(ApiException.class, (e, ctx) -> error(ctx, e.status(), e.code(), e.getMessage(), e.details()));
        routes.exception(IllegalArgumentException.class,
                (e, ctx) -> error(ctx, 400, "InvalidRequest", e.getMessage(), Map.of()));
        routes.exception(NoSuchElementException.class, (e, ctx) -> error(ctx, 404, "NotFound", e.getMessage(), Map.of()));
        routes.exception(com.fasterxml.jackson.core.JsonProcessingException.class,
                (e, ctx) -> error(ctx, 400, "InvalidRequest", "JSON invalide : " + e.getOriginalMessage(), Map.of()));
        routes.exception(io.fabric8.kubernetes.client.KubernetesClientException.class, (e, ctx) -> {
            int code = e.getCode();
            String reason = code == 409 ? "Conflict" : code == 404 ? "NotFound" : code == 422 || code == 400
                    ? "Invalid" : "KubernetesError";
            String message = e.getStatus() != null && e.getStatus().getMessage() != null ? e.getStatus().getMessage()
                    : e.getMessage();
            error(ctx, code >= 400 && code < 600 ? code : 502, reason, message, Map.of());
        });
        // Réponses HTTP de Javalin (UnauthorizedResponse, NotFoundResponse...) : statut conservé.
        routes.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> error(ctx, e.getStatus(),
                io.javalin.http.HttpStatus.forStatus(e.getStatus()).name(), e.getMessage(), Map.of()));
        routes.exception(Exception.class, (e, ctx) -> {
            LOG.warn("Erreur API {} {} : {}", ctx.method(), ctx.path(), e.toString(), e);
            error(ctx, 500, "InternalError", "erreur interne", Map.of());
        });
    }

    static void error(Context ctx, int status, String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? code : message);
        body.put("details", details == null ? Map.of() : details);
        ctx.status(status).json(body);
    }

    private void authenticate(Context ctx) {
        if (ctx.method().name().equals("OPTIONS") || PUBLIC.contains(ctx.path())) {
            return;
        }
        ApiAuthenticator.Principal p = auth.authenticate(ctx)
                .orElseThrow(() -> new ApiException(401, "Unauthorized", "authentification requise"));
        if (p.mustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(ctx.path())) {
            throw new ApiException(403, "PasswordChangeRequired",
                    "changez votre mot de passe (POST /api/v1/auth/password) avant toute autre action");
        }
        ctx.attribute(PRINCIPAL, p);
    }

    /** Utilisateur de la requête (posé par le filtre). */
    public static ApiAuthenticator.Principal principal(Context ctx) {
        ApiAuthenticator.Principal p = ctx.attribute(PRINCIPAL);
        if (p == null) {
            throw new ApiException(401, "Unauthorized", "authentification requise");
        }
        return p;
    }

    /** Lève 403 si l'utilisateur n'a pas la permission. */
    public static ApiAuthenticator.Principal require(Context ctx, String permission) {
        ApiAuthenticator.Principal p = principal(ctx);
        if (!p.has(permission)) {
            throw ApiException.forbidden(permission);
        }
        return p;
    }

    /** Corps JSON (objet vide si absent). */
    public static JsonNode body(Context ctx) throws java.io.IOException {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        JsonNode node = JSON.readTree(body);
        if (!node.isObject()) {
            throw ApiException.badRequest("objet JSON attendu");
        }
        return node;
    }

    /** Champ texte obligatoire du corps. */
    public static String text(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || !v.isTextual() || v.asText().isEmpty()) {
            throw ApiException.badRequest("champ obligatoire : " + field);
        }
        return v.asText();
    }
}
