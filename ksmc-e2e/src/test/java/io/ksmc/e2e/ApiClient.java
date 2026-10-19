package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.LocalPortForward;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Accès à l'API REST du controller pour les scénarios e2e : redirection de port et session admin. Au premier appel, le
 * mot de passe initial (Secret {@code ksmc-admin}) est remplacé par {@link #ADMIN_PASSWORD}, comme l'exige l'API.
 */
final class ApiClient implements AutoCloseable {

    static final String ADMIN_PASSWORD = "e2e-admin-password";
    static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final LocalPortForward forward;
    private String adminToken;

    ApiClient() {
        forward = CLIENT.services().inNamespace(NAMESPACE).withName("ksmc-controller").portForward(8080);
    }

    String base() {
        return "http://127.0.0.1:" + forward.getLocalPort();
    }

    int port() {
        return forward.getLocalPort();
    }

    static String initialAdminPassword() {
        return new String(Base64.getDecoder().decode(CLIENT.secrets().inNamespace(NAMESPACE).withName("ksmc-admin").get()
                .getData().get("password")), StandardCharsets.UTF_8);
    }

    HttpResponse<String> call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body)))
                    .header("Content-Type", "application/json");
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(method + " " + path + " : " + e, e);
        }
    }

    static JsonNode json(HttpResponse<String> r) {
        try {
            return JSON.readTree(r.body());
        } catch (Exception e) {
            throw new AssertionError("JSON attendu : " + r.body(), e);
        }
    }

    /** Session : jeton d'accès, ou {@code null} si la connexion échoue. */
    JsonNode login(String user, String password) {
        HttpResponse<String> r = call("POST", "/api/v1/auth/login", null, Map.of("username", user, "password", password));
        return r.statusCode() == 200 ? json(r) : null;
    }

    /** Jeton admin utilisable (mot de passe initial changé si nécessaire). */
    synchronized String admin() {
        if (adminToken != null) {
            return adminToken;
        }
        JsonNode session = login("admin", ADMIN_PASSWORD);
        if (session == null) {
            String initial = initialAdminPassword();
            JsonNode first = login("admin", initial);
            if (first == null) {
                throw new AssertionError("connexion admin impossible");
            }
            HttpResponse<String> changed = call("POST", "/api/v1/auth/password", first.get("accessToken").asText(),
                    Map.of("currentPassword", initial, "newPassword", ADMIN_PASSWORD));
            if (changed.statusCode() != 200) {
                throw new AssertionError("changement du mot de passe admin : " + changed.body());
            }
            session = json(changed);
        }
        adminToken = session.get("accessToken").asText();
        return adminToken;
    }

    @Override
    public void close() throws Exception {
        forward.close();
    }
}
