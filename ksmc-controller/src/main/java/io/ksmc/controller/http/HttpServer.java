package io.ksmc.controller.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.ksmc.controller.health.HealthRegistry;
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
        this.app = Javalin.create(config -> {
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
