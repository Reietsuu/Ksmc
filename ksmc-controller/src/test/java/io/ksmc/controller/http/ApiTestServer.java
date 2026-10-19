package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.controller.auth.AccountStore;
import io.ksmc.controller.auth.Jwt;
import io.ksmc.controller.health.HealthRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Serveur d'API de test : comptes dans un serveur Kubernetes simulé, jetons admin / operator / viewer prêts. */
final class ApiTestServer implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();

    final AccountStore accounts;
    final AuditLog audit;
    final HttpServer server;
    final String admin;
    final String operator;
    final String viewer;
    private final HttpClient http = HttpClient.newHttpClient();

    ApiTestServer(KubernetesClient client, List<HttpRoutes> routes) {
        this(client, routes, new AuditLog(null, "test", Clock.systemUTC(), List::of));
    }

    ApiTestServer(KubernetesClient client, List<HttpRoutes> routes, AuditLog audit) {
        this(client, auth -> routes, audit);
    }

    /** Routes construites avec l'authentificateur du serveur (WebSocket, console...). */
    ApiTestServer(KubernetesClient client, java.util.function.Function<ApiAuthenticator, List<HttpRoutes>> routes,
            AuditLog audit) {
        Clock clock = Clock.systemUTC();
        accounts = new AccountStore(client, "test", clock);
        accounts.bootstrap(null);
        accounts.createUser("root", "root-password-1", List.of("admin"), false);
        accounts.createUser("op", "operator-password", List.of("operator"), false);
        accounts.createUser("eve", "viewer-password", List.of("viewer"), false);
        admin = accounts.createToken("root", "test", null).value();
        operator = accounts.createToken("op", "test", null).value();
        viewer = accounts.createToken("eve", "test", null).value();
        this.audit = audit;
        Jwt jwt = new Jwt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), clock);
        SessionAuthenticator sessions = new SessionAuthenticator(accounts, jwt);
        List<HttpRoutes> all = new ArrayList<>();
        all.add(new ApiSupport(sessions));
        all.addAll(routes.apply(sessions));
        server = new HttpServer(new HealthRegistry(), all).start(0);
    }

    HttpResponse<String> call(String method, String path, String token, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    int port() {
        return server.port();
    }

    HttpResponse<String> call(String method, String path, String token, Object body) {
        return call(method, path, token, body, Map.of());
    }

    static JsonNode json(HttpResponse<String> r) {
        try {
            return JSON.readTree(r.body());
        } catch (Exception e) {
            throw new AssertionError("JSON attendu : " + r.body(), e);
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
