package io.ksmc.cli;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Faux controller : réponses fixes par « MÉTHODE /chemin?requête », requêtes enregistrées. */
final class FakeApi implements AutoCloseable {

    record Request(String method, String uri, String auth, byte[] body, Map<String, List<String>> headers) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    record Response(int status, String contentType, byte[] body) {
        static Response json(int status, String body) {
            return new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    final Map<String, Response> routes = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String uri = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            byte[] body = ex.getRequestBody().readAllBytes();
            requests.add(new Request(ex.getRequestMethod(), uri, ex.getRequestHeaders().getFirst("Authorization"), body,
                    Map.copyOf(ex.getRequestHeaders())));
            Response r = routes.get(ex.getRequestMethod() + " " + uri);
            if (r == null) {
                r = routes.get(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
            }
            if (r == null) {
                r = Response.json(404, "{\"code\":\"NotFound\",\"message\":\"route de test absente : " + uri + "\"}");
            }
            ex.getResponseHeaders().set("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
            if (r.body().length > 0) {
                ex.getResponseBody().write(r.body());
            }
            ex.close();
        });
        server.start();
    }

    FakeApi on(String methodAndPath, int status, String json) {
        routes.put(methodAndPath, Response.json(status, json));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Request last(String method) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).method().equals(method)) {
                return requests.get(i);
            }
        }
        throw new AssertionError("aucune requête " + method + " : " + requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
