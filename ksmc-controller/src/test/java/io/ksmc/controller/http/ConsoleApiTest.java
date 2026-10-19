package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class ConsoleApiTest {
    KubernetesClient client;
    ApiTestServer api;
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> followed = new CopyOnWriteArrayList<>();
    PipedOutputStream live;

    /** Logs simulés : 3 lignes d'historique puis ce qu'on écrit dans {@link #live}. */
    final ConsoleApi.LogSource logs = new ConsoleApi.LogSource() {
        public Follow follow(String pod, int lines) {
            followed.add(pod + ":" + lines);
            try {
                PipedInputStream in = new PipedInputStream(64 * 1024);
                live = new PipedOutputStream(in);
                live.write("[12:00] Starting\n[12:00] Done\n[12:01] Steve joined\n".getBytes(StandardCharsets.UTF_8));
                live.flush();
                return new Follow() {
                    public InputStream output() {
                        return in;
                    }

                    public void close() throws IOException {
                        live.close();
                    }
                };
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public String tail(String pod, int lines, boolean previous) {
            return (previous ? "précédent " : "courant ") + pod + " " + lines;
        }
    };

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(null, "test", java.time.Clock.systemUTC(), List::of);
        api = new ApiTestServer(client, auth -> List.of(new ConsoleApi(client, "test", auth, logs, (svc, cmd) -> {
            sent.add(svc + ":" + cmd);
            return "ok";
        }, audit)), audit);
        CloudService svc = new CloudService();
        svc.setMetadata(new ObjectMetaBuilder().withName("lobby-1").withNamespace("test").build());
        svc.setSpec(new CloudServiceSpec());
        svc.setStatus(new CloudServiceStatus());
        svc.getStatus().podName = "lobby-1";
        client.resource(svc).create();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    WebSocket console(String query, BlockingQueue<String> received) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + api.port()
                + "/api/v1/services/lobby-1/console?" + query), new WebSocket.Listener() {
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void consoleShowsHistoryThenLiveLinesAndRunsCommands() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket ws = console("lines=200&access_token=" + api.operator, received);

        assertThat(received.poll(3, TimeUnit.SECONDS)).contains("\"type\":\"log\"", "Starting");
        assertThat(received.poll(1, TimeUnit.SECONDS)).contains("Done");
        assertThat(received.poll(1, TimeUnit.SECONDS)).contains("Steve joined");
        assertThat(followed).containsExactly("lobby-1:200");

        long t = System.currentTimeMillis();
        live.write("[12:02] Alex joined\n".getBytes(StandardCharsets.UTF_8));
        live.flush();
        assertThat(received.poll(1, TimeUnit.SECONDS)).contains("Alex joined");
        assertThat(System.currentTimeMillis() - t).isLessThan(1000);

        ws.sendText("list", true).get(5, TimeUnit.SECONDS);
        assertThat(received.poll(3, TimeUnit.SECONDS)).contains("\"type\":\"command\"", "\"result\":\"ok\"");
        assertThat(sent).containsExactly("lobby-1:list");
        ws.sendText("ksmc:deploy x", true).get(5, TimeUnit.SECONDS);
        assertThat(received.poll(3, TimeUnit.SECONDS)).contains("\"type\":\"error\"", "réservé");
        assertThat(api.audit.query("op", "services.command", "lobby-1", 5)).hasSize(1);
    }

    @Test
    void consoleWithoutCommandPermissionIsReadOnly() throws Exception {
        api.accounts.putRole("watcher", List.of("ksmc.service.console", "ksmc.service.read"));
        api.accounts.createUser("watch", "watch-password", List.of("watcher"), false);
        String token = api.accounts.createToken("watch", "t", null).value();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket ws = console("access_token=" + token, received);
        for (int i = 0; i < 3; i++) {
            assertThat(received.poll(3, TimeUnit.SECONDS)).contains("\"type\":\"log\"");
        }
        ws.sendText("stop", true).get(5, TimeUnit.SECONDS);
        assertThat(received.poll(3, TimeUnit.SECONDS)).contains("permission manquante : ksmc.service.command");
        assertThat(sent).isEmpty();
    }

    @Test
    void viewerCannotOpenTheConsole() throws Exception {
        try {
            console("access_token=" + api.viewer, new LinkedBlockingQueue<>());
            throw new AssertionError("la console n'aurait pas dû s'ouvrir");
        } catch (java.util.concurrent.ExecutionException e) {
            assertThat(((java.net.http.WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(403);
        }
    }

    @Test
    void logsAndLastFailureExtract() {
        HttpResponse<String> current = api.call("GET", "/api/v1/services/lobby-1/logs?lines=50", api.viewer, null);
        assertThat(current.body()).isEqualTo("courant lobby-1 50");
        assertThat(api.call("GET", "/api/v1/services/lobby-1/logs?previous=true", api.viewer, null).body())
                .isEqualTo("précédent lobby-1 200");

        assertThat(api.call("GET", "/api/v1/services/lobby-1/lastlog", api.viewer, null).statusCode()).isEqualTo(404);
        client.configMaps().inNamespace("test").resource(new ConfigMapBuilder().withNewMetadata().withName("lobby-1-lastlog")
                .endMetadata().addToData("log", "java.lang.OutOfMemoryError").addToData("reason", "Crashed")
                .addToData("capturedAt", "2026-10-19T12:00:00Z").build()).create();
        HttpResponse<String> last = api.call("GET", "/api/v1/services/lobby-1/lastlog", api.viewer, null);
        assertThat(json(last).get("reason").asText()).isEqualTo("Crashed");
        assertThat(json(last).get("log").asText()).contains("OutOfMemoryError");
    }
}
