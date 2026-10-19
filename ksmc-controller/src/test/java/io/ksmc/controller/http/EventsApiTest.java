package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.wire.messaging.CloudEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class EventsApiTest {
    KubernetesClient client;
    ApiTestServer api;
    final FakeTransport transport = new FakeTransport();

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(null, "test", java.time.Clock.systemUTC(), List::of);
        api = new ApiTestServer(client, auth -> List.of(new EventsApi(auth, () -> transport, audit)), audit);
        audit.record("root", "services.stop", "lobby-2", "ok", null);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    static CloudEvent event(String type, String subject) {
        return CloudEvent.of(type, subject);
    }

    WebSocket socket(String query, BlockingQueue<String> received) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(
                URI.create("ws://127.0.0.1:" + api.port() + "/api/v1/events?" + query), new WebSocket.Listener() {
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    void awaitSubscriptions(int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (transport.subscriptions() != n && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(transport.subscriptions()).isEqualTo(n);
    }

    @Test
    void webSocketReceivesFilteredEventsWithinTwoSeconds() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket ws = socket("type=service.*&access_token=" + api.viewer, received);
        awaitSubscriptions(1);

        transport.publish(event("task.updated", "lobby"));
        transport.publish(event("service.started", "lobby-1"));

        String msg = received.poll(2, TimeUnit.SECONDS);
        assertThat(msg).contains("\"type\":\"service.started\"", "\"subject\":\"lobby-1\"", "\"sequence\":2");
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "fin").get(5, TimeUnit.SECONDS);
        awaitSubscriptions(0);
    }

    int handshakeStatus(String query) {
        try {
            socket(query, new LinkedBlockingQueue<>());
            return 101;
        } catch (Exception e) {
            Throwable c = e.getCause();
            assertThat(c).isInstanceOf(java.net.http.WebSocketHandshakeException.class);
            return ((java.net.http.WebSocketHandshakeException) c).getResponse().statusCode();
        }
    }

    @Test
    void webSocketRequiresAValidTokenWithEventRead() {
        assertThat(handshakeStatus("type=%3E")).isEqualTo(401);
        api.accounts.putRole("nothing", List.of("ksmc.task.read"));
        api.accounts.createUser("nobody", "nobody-password", List.of("nothing"), false);
        String token = api.accounts.createToken("nobody", "t", null).value();
        assertThat(handshakeStatus("access_token=" + token)).isEqualTo(403);
    }

    @Test
    void serverSentEventsStream() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                + "/api/v1/events/stream?type=service.ready")).header("Authorization", "Bearer " + api.viewer)
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        awaitSubscriptions(1);
        transport.publish(event("service.ready", "lobby-3"));

        BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        StringBuilder seen = new StringBuilder();
        long end = System.currentTimeMillis() + 3000;
        for (String line; System.currentTimeMillis() < end && (line = r.readLine()) != null; ) {
            seen.append(line).append('\n');
            if (line.startsWith("data:")) {
                break;
            }
        }
        assertThat(seen.toString()).contains("event: service.ready", "id: 1", "lobby-3");
        resp.body().close();
    }

    @Test
    void auditIsReadableByAdminsOnly() {
        HttpResponse<String> r = call("/api/v1/audit?target=lobby-2", api.admin);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).at("/items/0/user").asText()).isEqualTo("root");
        assertThat(json(r).at("/items/0/action").asText()).isEqualTo("services.stop");
        assertThat(json(r).at("/items/0/time").asText()).isNotEmpty();
        assertThat(call("/api/v1/audit", api.operator).statusCode()).isEqualTo(403);
    }

    HttpResponse<String> call(String path, String token) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port()
                    + path)).header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
