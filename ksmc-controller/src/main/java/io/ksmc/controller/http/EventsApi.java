package io.ksmc.controller.http;

import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.sse.SseClient;
import io.javalin.websocket.WsContext;
import io.ksmc.api.spi.AuditSink;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.wire.Wire;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.MessagingTransport;
import io.ksmc.wire.messaging.Subscription;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Flux temps réel des événements cloud : WebSocket {@code /api/v1/events} et Server-Sent Events
 * {@code /api/v1/events/stream}, filtres {@code type} ({@code service.*}, {@code >}...), {@code task},
 * {@code environment}, {@code since} (séquence) ; SSE : reprise par {@code Last-Event-ID}. Navigateurs : jeton en
 * {@code ?access_token=}. Audit : {@code GET /api/v1/audit}.
 */
public final class EventsApi implements HttpRoutes {

    private final ApiAuthenticator auth;
    private final Supplier<? extends MessagingTransport> messaging;
    private final AuditLog audit;
    private final Map<String, Subscription> sockets = new ConcurrentHashMap<>();

    public EventsApi(ApiAuthenticator auth, Supplier<? extends MessagingTransport> messaging, AuditLog audit) {
        this.auth = auth;
        this.messaging = messaging;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.wsBeforeUpgrade("/api/v1/events", this::authorizeUpgrade);
        routes.ws("/api/v1/events", ws -> {
            ws.onConnect(this::connect);
            ws.onClose(c -> close(c.sessionId()));
            ws.onError(c -> close(c.sessionId()));
        });
        routes.sse("/api/v1/events/stream", this::stream);
        routes.get("/api/v1/audit", this::audit);
    }

    static EventFilter filter(String type, String task, String environment, String since) {
        EventFilter f = EventFilter.type(type == null || type.isBlank() ? ">" : type);
        if (task != null) {
            f.task(task);
        }
        if (environment != null) {
            f.environment(environment);
        }
        if (since != null) {
            try {
                f.sinceSequence(Long.parseLong(since));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("since doit être un numéro de séquence");
            }
        }
        return f;
    }

    static String json(CloudEvent e) {
        JsonObject o = Wire.GSON.toJsonTree(e).getAsJsonObject();
        if (e.sequence > 0) {
            o.addProperty("sequence", e.sequence);
        }
        return o.toString();
    }

    private MessagingTransport transport() {
        MessagingTransport m = messaging.get();
        if (m == null) {
            throw new ApiException(503, "Unavailable", "messagerie indisponible");
        }
        return m;
    }

    // ---- WebSocket -----------------------------------------------------------------------------

    private void authorizeUpgrade(Context ctx) {
        ApiAuthenticator.Principal p = auth.authenticate(ctx).orElseThrow(UnauthorizedResponse::new);
        if (p.mustChangePassword() || !p.has(Permissions.EVENT_READ)) {
            throw new ForbiddenResponse("permission manquante : " + Permissions.EVENT_READ);
        }
    }

    private void connect(WsContext c) {
        try {
            EventFilter f = filter(c.queryParam("type"), c.queryParam("task"), c.queryParam("environment"),
                    c.queryParam("since"));
            sockets.put(c.sessionId(), transport().events().subscribe(f, e -> {
                try {
                    c.send(json(e));
                } catch (RuntimeException closed) {
                    close(c.sessionId());
                }
            }));
        } catch (ApiException e) {
            c.closeSession(1011, e.getMessage());
        }
    }

    private void close(String sessionId) {
        Subscription s = sockets.remove(sessionId);
        if (s != null) {
            s.close();
        }
    }

    // ---- Server-Sent Events --------------------------------------------------------------------

    private void stream(SseClient client) {
        Context ctx = client.ctx();
        ApiSupport.require(ctx, Permissions.EVENT_READ);
        String since = ctx.queryParam("since");
        String lastId = ctx.header("Last-Event-ID");
        if (lastId != null && lastId.matches("\\d+")) {
            since = String.valueOf(Long.parseLong(lastId) + 1);
        }
        EventFilter f = filter(ctx.queryParam("type"), ctx.queryParam("task"), ctx.queryParam("environment"), since);
        Subscription s = transport().events().subscribe(f, e -> {
            try {
                client.sendEvent(e.type, json(e), e.sequence > 0 ? String.valueOf(e.sequence) : null);
            } catch (RuntimeException closed) {
                client.close();
            }
        });
        client.onClose(s::close);
        client.sendComment("connected");
        client.keepAlive();
    }

    // ---- Audit ---------------------------------------------------------------------------------

    private void audit(Context ctx) {
        ApiSupport.require(ctx, Permissions.AUDIT_READ);
        int limit = 100;
        if (ctx.queryParam("limit") != null) {
            limit = Math.max(1, Math.min(AuditLog.KEEP, Integer.parseInt(ctx.queryParam("limit"))));
        }
        List<AuditSink.Entry> entries = audit.query(ctx.queryParam("user"), ctx.queryParam("action"),
                ctx.queryParam("target"), limit);
        ctx.json(Map.of("items", entries));
    }
}
