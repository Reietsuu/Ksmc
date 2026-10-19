package io.ksmc.api.module;

import java.util.List;
import java.util.Map;

/** Routes REST d'un module, relatives à {@code /api/v1/modules/<id>/}. */
public interface RestRoutes {

    enum Method { GET, POST, PUT, PATCH, DELETE }

    /**
     * @param path       chemin relatif, paramètres sous la forme {@code {name}} (ex. {@code greet/{who}})
     * @param permission permission exigée (ex. {@code hello.read}), ou {@code null} : authentifié suffit
     */
    void route(Method method, String path, String permission, Handler handler);

    default void get(String path, String permission, Handler handler) {
        route(Method.GET, path, permission, handler);
    }

    default void post(String path, String permission, Handler handler) {
        route(Method.POST, path, permission, handler);
    }

    @FunctionalInterface
    interface Handler {
        Response handle(Request request) throws Exception;
    }

    record Request(Method method, String path, Map<String, String> pathParams, Map<String, List<String>> query,
            Map<String, String> headers, String body, String user) {

        public String pathParam(String name) {
            return pathParams.get(name);
        }

        public String queryParam(String name) {
            List<String> values = query.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** Réponse : {@code body} est sérialisé en JSON (une {@link String} est envoyée telle quelle en texte). */
    record Response(int status, Object body) {

        public static Response ok(Object body) {
            return new Response(200, body);
        }

        public static Response status(int status, Object body) {
            return new Response(status, body);
        }
    }
}
