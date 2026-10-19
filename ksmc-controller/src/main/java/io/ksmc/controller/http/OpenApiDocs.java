package io.ksmc.controller.http;

import io.javalin.config.RoutesConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Description OpenAPI 3 de l'API ({@code /api/v1/openapi.json}, sans authentification) et Swagger UI
 * ({@code /api/docs}, chargé depuis un CDN par le navigateur). Source : {@code scripts/gen-openapi.py}.
 */
public final class OpenApiDocs implements HttpRoutes {

    static final String SWAGGER_UI = "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14";
    private final byte[] spec;

    public OpenApiDocs() {
        try (InputStream in = OpenApiDocs.class.getResourceAsStream("/api/openapi.json")) {
            if (in == null) {
                throw new IllegalStateException("ressource /api/openapi.json absente");
            }
            spec = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.get("/api/v1/openapi.json", ctx -> ctx.contentType("application/json").result(spec));
        routes.get("/api/docs", ctx -> ctx.contentType("text/html; charset=utf-8").result("""
                <!doctype html>
                <html lang="fr">
                <head>
                  <meta charset="utf-8">
                  <title>Ksmc API</title>
                  <link rel="stylesheet" href="%1$s/swagger-ui.css">
                </head>
                <body>
                  <div id="swagger"></div>
                  <script src="%1$s/swagger-ui-bundle.js"></script>
                  <script>
                    SwaggerUIBundle({url: '/api/v1/openapi.json', dom_id: '#swagger', persistAuthorization: true});
                  </script>
                </body>
                </html>
                """.formatted(SWAGGER_UI)));
    }
}
