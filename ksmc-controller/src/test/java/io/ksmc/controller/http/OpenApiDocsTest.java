package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.controller.auth.Jwt;
import io.ksmc.controller.auth.LoginRateLimiter;
import io.ksmc.controller.bridge.PlayerService;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class OpenApiDocsTest {
    KubernetesClient client;
    @TempDir
    Path tmp;
    ApiTestServer api;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.systemUTC();
        AuditLog audit = new AuditLog(null, "test", clock, List::of);
        FileArtifactStorage storage = new FileArtifactStorage(tmp);
        ExtensionRegistry extensions = new ExtensionRegistry();
        PlayerService players = new PlayerService(() -> null);
        ActionApi.Commands commands = (s, c) -> "ok";
        Jwt jwt = new Jwt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), clock);
        api = new ApiTestServer(client, auth -> List.of(
                new AuthApi(((SessionAuthenticator) auth).accounts(), jwt,
                        new LoginRateLimiter(10, Duration.ofMinutes(1), clock),
                        new LoginRateLimiter(50, Duration.ofMinutes(1), clock), extensions, audit),
                new ActionApi(client, "test", commands, players, audit),
                new ResourceApi(client, "test", players, audit),
                new TemplateFilesApi(storage, audit),
                new EventsApi(auth, () -> null, audit),
                new ConsoleApi(client, "test", auth, ConsoleApi.kubernetes(client, "test"), commands, audit),
                new OpenApiDocs(),
                new LogLevelApi(audit),
                new ModuleApi(extensions, auth)), audit);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    static boolean routeMissing(HttpResponse<String> r) {
        return r.statusCode() == 404 && r.body().contains("Endpoint ") && r.body().contains("not found");
    }

    @Test
    void specIsPublicAndValid() {
        HttpResponse<String> r = api.call("GET", "/api/v1/openapi.json", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode spec = json(r);
        assertThat(spec.get("openapi").asText()).isEqualTo("3.0.3");
        assertThat(spec.get("paths").size()).isGreaterThan(40);
        assertThat(api.call("GET", "/api/docs", null, null).body()).contains("swagger-ui", "/api/v1/openapi.json");
        // Signature d'une route inexistante, pour la vérification suivante.
        assertThat(routeMissing(api.call("GET", "/api/v1/nexistepas", api.admin, null))).isTrue();
    }

    @Test
    void everyDocumentedOperationIsARealRoute() {
        JsonNode paths = json(api.call("GET", "/api/v1/openapi.json", null, null)).get("paths");
        Map<String, String> samples = Map.of("{name}", "lobby", "{id}", "hello", "{player}", "Steve",
                "{template}", "lobby/default", "{file}", "server.properties");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (var path = paths.fields(); path.hasNext(); ) {
            var entry = path.next();
            String concrete = entry.getKey();
            for (var s : samples.entrySet()) {
                concrete = concrete.replace(s.getKey(), s.getValue());
            }
            for (var method = entry.getValue().fields(); method.hasNext(); ) {
                var m = method.next();
                if (m.getValue().get("responses").has("101")) {
                    continue; // WebSocket : vérifié par EventsApiTest et ConsoleApiTest.
                }
                HttpResponse<String> r = api.call(m.getKey().toUpperCase(), concrete, api.admin,
                        m.getKey().equals("get") || m.getKey().equals("delete") ? null : "{}");
                checked++;
                if (routeMissing(r)) {
                    missing.add(m.getKey().toUpperCase() + " " + entry.getKey());
                }
            }
        }
        assertThat(checked).isGreaterThan(60);
        assertThat(missing).isEmpty();
    }
}
