package io.ksmc.controller.http;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.websocket.WsContext;
import io.ksmc.api.model.CloudService;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.service.LastLog;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Console des services : WebSocket {@code /api/v1/services/<nom>/console} (les {@code lines} dernières lignes, défaut
 * 200, puis le direct ; chaque message texte reçu est une commande, avec {@code ksmc.service.command}). Messages
 * envoyés en JSON : {@code {"type":"log","line":...}}, {@code {"type":"command",...}}, {@code {"type":"error",...}}.
 * Aussi : {@code GET .../logs} (texte, {@code ?previous=true} : démarrage précédent) et {@code GET .../lastlog}
 * (extrait conservé du dernier échec).
 */
public final class ConsoleApi implements HttpRoutes {

    static final int DEFAULT_LINES = 200;
    static final int MAX_LINES = 5000;

    /** Accès aux logs du conteneur {@code server} d'un Pod. */
    public interface LogSource {
        /** Les {@code lines} dernières lignes puis la suite en continu (fermer pour arrêter). */
        Follow follow(String pod, int lines);

        String tail(String pod, int lines, boolean previous);

        interface Follow extends Closeable {
            InputStream output();
        }
    }

    /** Implémentation fabric8. */
    public static LogSource kubernetes(KubernetesClient client, String namespace) {
        return new LogSource() {
            public Follow follow(String pod, int lines) {
                LogWatch w = client.pods().inNamespace(namespace).withName(pod).inContainer("server").tailingLines(lines)
                        .watchLog();
                return new Follow() {
                    public InputStream output() {
                        return w.getOutput();
                    }

                    public void close() {
                        w.close();
                    }
                };
            }

            public String tail(String pod, int lines, boolean previous) {
                var p = client.pods().inNamespace(namespace).withName(pod).inContainer("server");
                return previous ? p.terminated().tailingLines(lines).getLog() : p.tailingLines(lines).getLog();
            }
        };
    }

    private final KubernetesClient client;
    private final String namespace;
    private final ApiAuthenticator auth;
    private final LogSource logs;
    private final ActionApi.Commands commands;
    private final AuditLog audit;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(ApiAuthenticator.Principal user, String service, LogSource.Follow follow) {
    }

    public ConsoleApi(KubernetesClient client, String namespace, ApiAuthenticator auth, LogSource logs,
            ActionApi.Commands commands, AuditLog audit) {
        this.client = client;
        this.namespace = namespace;
        this.auth = auth;
        this.logs = logs;
        this.commands = commands;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.wsBeforeUpgrade("/api/v1/services/{name}/console", this::authorizeUpgrade);
        routes.ws("/api/v1/services/{name}/console", ws -> {
            ws.onConnect(this::connect);
            ws.onMessage(c -> command(c, c.message()));
            ws.onClose(c -> close(c.sessionId()));
            ws.onError(c -> close(c.sessionId()));
        });
        routes.get("/api/v1/services/{name}/logs", this::logs);
        routes.get("/api/v1/services/{name}/lastlog", this::lastLog);
    }

    private CloudService service(String name) {
        return client.resources(CloudService.class).inNamespace(namespace).withName(name).get();
    }

    private static String pod(CloudService svc) {
        return svc.getStatus() != null && svc.getStatus().podName != null ? svc.getStatus().podName
                : svc.getMetadata().getName();
    }

    static int lines(String value) {
        if (value == null) {
            return DEFAULT_LINES;
        }
        try {
            return Math.max(1, Math.min(MAX_LINES, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("lines doit être un entier");
        }
    }

    // ---- WebSocket -----------------------------------------------------------------------------

    private void authorizeUpgrade(Context ctx) {
        ApiAuthenticator.Principal p = auth.authenticate(ctx).orElseThrow(UnauthorizedResponse::new);
        if (p.mustChangePassword() || !p.has(Permissions.SERVICE_CONSOLE)) {
            throw new ForbiddenResponse("permission manquante : " + Permissions.SERVICE_CONSOLE);
        }
        if (service(ctx.pathParam("name")) == null) {
            throw new NotFoundResponse("service introuvable : " + ctx.pathParam("name"));
        }
    }

    private static String token(WsContext c) {
        String header = c.header("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : c.queryParam("access_token");
    }

    private static String message(String type, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.putAll(fields);
        try {
            return ApiSupport.JSON.writeValueAsString(m);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void connect(WsContext c) {
        ApiAuthenticator.Principal user = auth.authenticateToken(token(c)).orElse(null);
        CloudService svc = service(c.pathParam("name"));
        if (user == null || svc == null) {
            c.closeSession(1008, "accès refusé");
            return;
        }
        LogSource.Follow follow;
        try {
            follow = logs.follow(pod(svc), lines(c.queryParam("lines")));
        } catch (RuntimeException e) {
            c.send(message("error", Map.of("message", "logs indisponibles : " + e.getMessage())));
            c.closeSession(1011, "logs indisponibles");
            return;
        }
        sessions.put(c.sessionId(), new Session(user, svc.getMetadata().getName(), follow));
        Thread.ofVirtual().name("ksmc-console-" + svc.getMetadata().getName()).start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(follow.output(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    c.send(message("log", Map.of("line", line)));
                }
                c.send(message("end", Map.of("message", "fin des logs (service arrêté)")));
            } catch (IOException | RuntimeException e) {
                // console fermée
            } finally {
                close(c.sessionId());
            }
        });
    }

    private void command(WsContext c, String raw) {
        Session s = sessions.get(c.sessionId());
        if (s == null) {
            return;
        }
        String command = raw == null ? "" : raw.trim();
        if (command.isEmpty()) {
            return;
        }
        if (!s.user().has(Permissions.SERVICE_COMMAND)) {
            c.send(message("error", Map.of("message", "permission manquante : " + Permissions.SERVICE_COMMAND)));
            return;
        }
        if (command.startsWith(ActionApi.CONTROL_PREFIX)) {
            c.send(message("error", Map.of("message", "préfixe réservé : " + ActionApi.CONTROL_PREFIX)));
            return;
        }
        String result;
        try {
            result = commands.send(s.service(), command);
        } catch (RuntimeException e) {
            result = "error: " + e.getMessage();
        }
        audit.record(s.user().name(), "services.command", s.service(), result.startsWith("error") ? "error" : "ok", command);
        c.send(message("command", Map.of("command", command, "result", result)));
    }

    private void close(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s != null) {
            try {
                s.follow().close();
            } catch (IOException | RuntimeException ignored) {
                // déjà fermé
            }
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    private void logs(Context ctx) {
        ApiSupport.require(ctx, Permissions.SERVICE_READ);
        CloudService svc = service(ctx.pathParam("name"));
        if (svc == null) {
            throw ApiException.notFound("services/" + ctx.pathParam("name") + " introuvable");
        }
        String text;
        try {
            text = logs.tail(pod(svc), lines(ctx.queryParam("lines")), "true".equals(ctx.queryParam("previous")));
        } catch (RuntimeException e) {
            throw new ApiException(404, "LogsUnavailable", "logs indisponibles (Pod absent ?) : " + e.getMessage());
        }
        ctx.contentType("text/plain; charset=utf-8").result(text == null ? "" : text);
    }

    private void lastLog(Context ctx) {
        ApiSupport.require(ctx, Permissions.SERVICE_READ);
        String name = ctx.pathParam("name");
        ConfigMap cm = client.configMaps().inNamespace(namespace).withName(LastLog.name(name)).get();
        if (cm == null || cm.getData() == null) {
            throw ApiException.notFound("aucun échec enregistré pour " + name);
        }
        ctx.json(Map.of("service", name, "reason", cm.getData().getOrDefault("reason", ""),
                "capturedAt", cm.getData().getOrDefault("capturedAt", ""), "log", cm.getData().getOrDefault("log", "")));
    }
}
