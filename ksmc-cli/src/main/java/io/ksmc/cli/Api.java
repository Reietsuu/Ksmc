package io.ksmc.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Client de l'API REST : JSON, {@code Authorization: Bearer}, renouvellement automatique de la session sur 401 (le
 * nouveau jeton est enregistré dans le contexte). Erreurs de l'API → {@link CliException} (code 2).
 */
public final class Api {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String server;
    private final Config config;
    private final Config.Context context;

    /** @param config configuration à enregistrer après un renouvellement, ou {@code null} */
    public Api(String server, Config.Context context, Config config) {
        this.server = server.replaceAll("/+$", "");
        this.context = context;
        this.config = config;
    }

    public String server() {
        return server;
    }

    public String token() {
        return context == null ? null : context.token;
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Segments de chemin encodés, {@code /} conservés (chemins de templates et de fichiers). */
    public static String encodePath(String value) {
        StringBuilder sb = new StringBuilder();
        for (String part : value.split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(encode(part));
        }
        return sb.toString();
    }

    public static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (v != null) {
                sb.append(sb.isEmpty() ? '?' : '&').append(encode(k)).append('=').append(encode(v));
            }
        });
        return sb.toString();
    }

    private HttpRequest.Builder request(String method, String path, HttpRequest.BodyPublisher body,
            Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(60))
                .method(method, body);
        if (token() != null) {
            b.header("Authorization", "Bearer " + token());
        }
        headers.forEach(b::header);
        return b;
    }

    /** Requête brute (corps binaire) avec renouvellement de session. */
    public HttpResponse<InputStream> send(String method, String path, byte[] body, Map<String, String> headers) {
        try {
            HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body);
            HttpResponse<InputStream> r = HTTP.send(request(method, path, publisher, headers).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() == 401 && refresh()) {
                r.body().close();
                r = HTTP.send(request(method, path, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body), headers).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            }
            return r;
        } catch (IOException e) {
            throw CliException.api("connexion à " + server + " impossible : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CliException.api("interrompu");
        }
    }

    /** Requête JSON ; renvoie le corps (nœud vide si aucun) ou lève l'erreur de l'API. */
    public JsonNode call(String method, String path, Object body, Map<String, String> headers) {
        byte[] payload = null;
        try {
            if (body instanceof byte[] bytes) {
                payload = bytes;
            } else if (body instanceof String s) {
                payload = s.getBytes(StandardCharsets.UTF_8);
            } else if (body != null) {
                payload = JSON.writeValueAsBytes(body);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> h = new java.util.LinkedHashMap<>(headers);
        if (payload != null && !(body instanceof byte[])) {
            h.putIfAbsent("Content-Type", "application/json");
        }
        HttpResponse<InputStream> r = send(method, path, payload, h);
        byte[] bytes;
        try (InputStream in = r.body()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw CliException.api("réponse illisible : " + e.getMessage());
        }
        if (r.statusCode() >= 400) {
            throw error(r.statusCode(), bytes);
        }
        if (bytes.length == 0) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            return JSON.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    public JsonNode get(String path) {
        return call("GET", path, null, Map.of());
    }

    public JsonNode post(String path, Object body) {
        return call("POST", path, body, Map.of());
    }

    public JsonNode put(String path, Object body) {
        return call("PUT", path, body, Map.of());
    }

    public JsonNode delete(String path) {
        return call("DELETE", path, null, Map.of());
    }

    /** Erreur uniforme de l'API ({@code code: message}). */
    static CliException error(int status, byte[] body) {
        try {
            JsonNode n = JSON.readTree(body);
            if (n != null && n.has("code")) {
                String msg = n.path("message").asText(n.get("code").asText());
                JsonNode perm = n.path("details").path("permission");
                return CliException.api(n.get("code").asText() + " : " + msg
                        + (perm.isTextual() && !msg.contains(perm.asText()) ? " (" + perm.asText() + ")" : ""));
            }
        } catch (IOException ignored) {
            // corps non JSON
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        return CliException.api("HTTP " + status + (text.isEmpty() ? "" : " : " + text));
    }

    /** Renouvelle la session avec le jeton de renouvellement du contexte ; vrai si réussi. */
    private boolean refresh() throws IOException, InterruptedException {
        if (context == null || context.refreshToken == null) {
            return false;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(server + "/api/v1/auth/refresh"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("refreshToken", context.refreshToken)))).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            return false;
        }
        JsonNode session = JSON.readTree(r.body());
        context.token = session.path("accessToken").asText();
        context.refreshToken = session.path("refreshToken").asText(context.refreshToken);
        if (config != null) {
            config.save();
        }
        return true;
    }
}
