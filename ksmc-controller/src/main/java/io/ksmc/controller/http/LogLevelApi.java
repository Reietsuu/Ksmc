package io.ksmc.controller.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.controller.auth.Permissions;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.LoggerFactory;

/**
 * Niveau des logs du controller modifiable à chaud : {@code GET /api/v1/admin/loglevel} (niveaux définis) et
 * {@code PUT /api/v1/admin/loglevel} {@code {"logger": "io.ksmc", "level": "DEBUG"}} (logger racine si absent ;
 * {@code level: null} rétablit l'héritage). Permission {@code ksmc.admin.logs}.
 */
public final class LogLevelApi implements HttpRoutes {

    static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private final AuditLog audit;

    public LogLevelApi(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.get("/api/v1/admin/loglevel", this::list);
        routes.put("/api/v1/admin/loglevel", this::set);
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private void list(Context ctx) {
        ApiSupport.require(ctx, Permissions.ADMIN_LOGS);
        Map<String, String> out = new TreeMap<>();
        for (Logger l : context().getLoggerList()) {
            if (l.getLevel() != null) {
                out.put(l.getName(), l.getLevel().toString());
            }
        }
        ctx.json(out);
    }

    private void set(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.ADMIN_LOGS);
        JsonNode body = ApiSupport.body(ctx);
        String name = body.hasNonNull("logger") && !body.get("logger").asText().isBlank() ? body.get("logger").asText()
                : org.slf4j.Logger.ROOT_LOGGER_NAME;
        String level = body.hasNonNull("level") ? body.get("level").asText().toUpperCase() : null;
        if (level != null && !LEVELS.contains(level)) {
            throw ApiException.badRequest("niveau inconnu : " + level + " (attendu : " + new java.util.TreeSet<>(LEVELS) + ")");
        }
        if (level == null && name.equals(org.slf4j.Logger.ROOT_LOGGER_NAME)) {
            throw ApiException.badRequest("le logger racine doit avoir un niveau");
        }
        Logger logger = context().getLogger(name);
        logger.setLevel(level == null ? null : Level.toLevel(level));
        audit.record(p.name(), "admin.loglevel", name, "ok", level == null ? "hérité" : level);
        ctx.json(Map.of("logger", name, "level", level == null ? "inherited" : level,
                "effective", logger.getEffectiveLevel().toString()));
    }
}
