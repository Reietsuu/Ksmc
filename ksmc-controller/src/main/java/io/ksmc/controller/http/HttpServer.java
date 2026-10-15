package io.ksmc.controller.http;

import io.javalin.Javalin;
import io.ksmc.controller.health.HealthRegistry;
import java.util.List;
import java.util.Map;

/**
 * Serveur HTTP du controller. Pour l'instant : sondes de santé.
 * L'API REST ({@code /api/v1}) et le dashboard s'y ajoutent dans les changes suivants.
 */
public final class HttpServer implements AutoCloseable {
    private final Javalin app;

    public HttpServer(HealthRegistry health) {
        this(health, List.of());
    }

    public HttpServer(HealthRegistry health, List<HttpRoutes> extraRoutes) {
        this.app = Javalin.create(config -> {
            extraRoutes.forEach(r -> r.register(config.routes));
            config.startup.showJavalinBanner = false;
            config.routes.get("/healthz", ctx -> ctx.json(Map.of("status", "ok")));
            config.routes.get("/readyz", ctx -> {
                HealthRegistry.Report report = health.readiness();
                ctx.status(report.ready() ? 200 : 503).json(report);
            });
        });
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
