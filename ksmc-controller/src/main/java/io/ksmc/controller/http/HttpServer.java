package io.ksmc.controller.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.metrics.ControllerMetrics;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Serveur HTTP du controller : sondes de santé, routes fournies (API REST {@code /api/v1}...) et dashboard web
 * (ressources classpath {@code /dashboard}, servies sur {@code /} ; toute URL inconnue hors API renvoie
 * {@code index.html} pour le routage côté navigateur).
 */
public final class HttpServer implements AutoCloseable {
    static final String DASHBOARD = "/dashboard";

    private final Javalin app;

    public HttpServer(HealthRegistry health) {
        this(health, List.of());
    }

    public HttpServer(HealthRegistry health, List<HttpRoutes> extraRoutes) {
        this(health, extraRoutes, null);
    }

    /** Avec {@code metrics} : {@code GET /metrics} (format Prometheus, sans authentification) et durée des requêtes. */
    public HttpServer(HealthRegistry health, List<HttpRoutes> extraRoutes, ControllerMetrics metrics) {
        this.app = Javalin.create(config -> {
            if (metrics != null) {
                config.routes.get("/metrics", ctx -> ctx.contentType("text/plain; version=0.0.4; charset=utf-8")
                        .result(metrics.scrape()));
                config.requestLogger.http((ctx, ms) -> {
                    String route = ctx.endpoint() == null ? "unmatched" : ctx.endpoint().path;
                    if (route.startsWith("/api/")) {
                        metrics.apiRequest(ctx.method().name(), route, ctx.statusCode(), ms);
                    }
                });
            }
            extraRoutes.forEach(r -> r.register(config.routes));
            config.startup.showJavalinBanner = false;
            if (HttpServer.class.getResource(DASHBOARD + "/index.html") != null) {
                config.staticFiles.add(files -> {
                    files.hostedPath = "/";
                    files.directory = DASHBOARD;
                    files.location = Location.CLASSPATH;
                });
                config.spaRoot.addHandler("/", HttpServer::spaFallback);
            }
            config.routes.get("/healthz", ctx -> ctx.json(Map.of("status", "ok")));
            config.routes.get("/readyz", ctx -> {
                HealthRegistry.Report report = health.readiness();
                ctx.status(report.ready() ? 200 : 503).json(report);
            });
        });
    }

    /** URL inconnue : index.html du dashboard, sauf pour l'API et les bundles de modules (404 JSON). */
    private static void spaFallback(Context ctx) throws Exception {
        String path = ctx.path();
        if (path.startsWith("/api/") || path.startsWith("/modules/")) {
            ApiSupport.error(ctx, 404, "NotFound", "Endpoint " + ctx.method() + " " + path + " not found", Map.of());
            return;
        }
        try (InputStream in = HttpServer.class.getResourceAsStream(DASHBOARD + "/index.html")) {
            ctx.status(200).header("Cache-Control", "no-cache").html(new String(in.readAllBytes(),
                    StandardCharsets.UTF_8));
        }
    }

    public HttpServer start(int port) {
        app.start(port);
        return this;
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }
}
