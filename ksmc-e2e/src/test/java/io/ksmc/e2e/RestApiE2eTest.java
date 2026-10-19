package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.ServiceTask;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Scénarios du change p08 : comptes, CRUD REST, permissions, console, flux d'événements, exemples de docs/API.md. */
@Order(1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestApiE2eTest {

    static final Duration TIMEOUT = Duration.ofMinutes(6);
    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (String t : List.of("e2er-lobby", "e2er-proxy", "doc-lobby")) {
            CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName(t).delete();
        }
        api.close();
    }

    static JsonNode json(HttpResponse<String> r) {
        return ApiClient.json(r);
    }

    @Test
    @Order(1)
    void initialAdminMustChangeItsPassword() {
        String initial = ApiClient.initialAdminPassword();
        JsonNode first = api.login("admin", initial);
        Assumptions.assumeTrue(first != null, "mot de passe initial déjà changé (cluster réutilisé)");
        assertThat(first.get("mustChangePassword").asBoolean()).isTrue();
        String token = first.get("accessToken").asText();

        HttpResponse<String> blocked = api.call("GET", "/api/v1/tasks", token, null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).get("code").asText()).isEqualTo("PasswordChangeRequired");
        assertThat(api.call("GET", "/api/v1/tasks", null, null).statusCode()).isEqualTo(401);

        HttpResponse<String> changed = api.call("POST", "/api/v1/auth/password", token,
                Map.of("currentPassword", initial, "newPassword", ApiClient.ADMIN_PASSWORD));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(api.call("GET", "/api/v1/tasks", json(changed).get("accessToken").asText(), null).statusCode())
                .isEqualTo(200);
    }

    @Test
    @Order(2)
    void taskCreatedByRestIsSeenByKubectlAndViceVersa() {
        HttpResponse<String> created = api.call("POST", "/api/v1/tasks", api.admin(), Map.of(
                "metadata", Map.of("name", "e2er-lobby"),
                "spec", Map.of("environment", "server", "software", Map.of("name", "paper", "version", "latest"),
                        "minServiceCount", 0)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        ServiceTask task = CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2er-lobby").get();
        assertThat(task).isNotNull();

        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2er-lobby").edit(t -> {
            t.getSpec().maxServiceCount = 7;
            return t;
        });
        HttpResponse<String> read = api.call("GET", "/api/v1/tasks/e2er-lobby", api.admin(), null);
        assertThat(json(read).at("/spec/maxServiceCount").asInt()).isEqualTo(7);
    }

    @Test
    @Order(3)
    void viewerIsForbiddenToDelete() {
        HttpResponse<String> account = api.call("POST", "/api/v1/accounts", api.admin(), Map.of("name", "e2e-viewer",
                "password", "e2e-viewer-password", "roles", List.of("viewer"), "mustChangePassword", false));
        assertThat(account.statusCode()).isIn(201, 409);
        String viewer = api.login("e2e-viewer", "e2e-viewer-password").get("accessToken").asText();

        HttpResponse<String> r = api.call("DELETE", "/api/v1/tasks/e2er-lobby", viewer, null);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).at("/details/permission").asText()).isEqualTo("ksmc.task.write");
        assertThat(api.call("GET", "/api/v1/tasks/e2er-lobby", viewer, null).statusCode()).isEqualTo(200);
    }

    WebSocket socket(String path, List<String> received) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + api.port()
                + path), new WebSocket.Listener() {
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return null;
                    }
                }).get(10, TimeUnit.SECONDS);
    }

    @Test
    @Order(4)
    void eventStreamAndConsoleCommand() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        WebSocket eventSocket = socket("/api/v1/events?type=service.*&access_token=" + api.admin(), events);

        HttpResponse<String> created = api.call("POST", "/api/v1/tasks", api.admin(), Map.of(
                "metadata", Map.of("name", "e2er-proxy"),
                "spec", Map.of("environment", "proxy", "software", Map.of("name", "velocity", "version", "latest"),
                        "memoryMb", 512, "minServiceCount", 1)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        await("e2er-proxy-1 Running", TIMEOUT, () -> {
            CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName("e2er-proxy-1").get();
            return s != null && s.getStatus() != null && s.getStatus().phase == CloudServiceStatus.Phase.Running;
        });
        await("événement du service reçu", Duration.ofSeconds(10),
                () -> events.stream().anyMatch(e -> e.contains("\"subject\":\"e2er-proxy-1\"")));
        eventSocket.sendClose(WebSocket.NORMAL_CLOSURE, "fin");

        List<String> console = new CopyOnWriteArrayList<>();
        WebSocket ws = socket("/api/v1/services/e2er-proxy-1/console?lines=50&access_token=" + api.admin(), console);
        await("historique de la console", Duration.ofSeconds(20), () -> console.stream().anyMatch(m -> m.contains("\"log\"")));
        int before = console.size();
        ws.sendText("velocity info", true).get(5, TimeUnit.SECONDS);
        await("accusé de la commande", Duration.ofSeconds(10),
                () -> console.stream().anyMatch(m -> m.contains("\"command\":\"velocity info\"") && m.contains("\"ok\"")));
        await("sortie de la commande", Duration.ofSeconds(20),
                () -> console.subList(before, console.size()).stream().anyMatch(m -> m.contains("\"log\"") && m.contains("Velocity")));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin");

        HttpResponse<String> audit = api.call("GET", "/api/v1/audit?action=services.command&target=e2er-proxy-1",
                api.admin(), null);
        assertThat(json(audit).at("/items/0/detail").asText()).isEqualTo("velocity info");
    }

    /** Exécute dans l'ordre les blocs {@code # e2e} de docs/API.md (une seule session bash). */
    @Test
    @Order(5)
    void curlExamplesOfTheApiGuideWork() throws Exception {
        Path doc = E2eSupport.EXAMPLES.getParent().resolve("docs/API.md");
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            if (current == null && line.equals("```bash")) {
                current = new StringBuilder();
            } else if (current != null && line.equals("```")) {
                if (current.toString().startsWith("# e2e\n")) {
                    blocks.add(current.toString());
                }
                current = null;
            } else if (current != null) {
                current.append(line).append('\n');
            }
        }
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(6);
        api.admin();
        String script = "set -euo pipefail\n" + String.join("\n", blocks);
        Path file = Files.createTempFile("ksmc-api-doc", ".sh");
        Files.writeString(file, script);
        ProcessBuilder pb = new ProcessBuilder("bash", file.toString()).redirectErrorStream(true);
        pb.environment().put("KSMC", api.base());
        pb.environment().put("PASSWORD", ApiClient.ADMIN_PASSWORD);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("exemples de docs/API.md :\n" + output).isZero();
    }
}
