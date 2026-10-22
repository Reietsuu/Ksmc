package io.ksmc.controller.bridge;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.controller.messaging.CloudEvents;
import io.ksmc.controller.messaging.Snapshots;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.KeyValueApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Côté controller des bridges : publie les {@link ServiceInfo} dans le KV {@code services}, applique les rapports
 * des bridges (KV immédiatement, statut Kubernetes au plus toutes les {@value #STATUS_MIN_INTERVAL_SECONDS} s)
 * et relaie les événements joueurs en événements cloud.
 */
public final class BridgeHub {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeHub.class);
    static final long STATUS_MIN_INTERVAL_SECONDS = 2;
    public static final Duration SERVICES_TTL = Duration.ofSeconds(60);
    public static final Duration PLAYERS_TTL = Duration.ofSeconds(30);

    private final KubernetesClient client;
    private final String namespace;
    private final CloudEvents events;
    private final Clock clock;
    private final Map<String, ServiceReport> reports = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastStatusPatch = new ConcurrentHashMap<>();
    private volatile NatsMessaging messaging;

    public BridgeHub(KubernetesClient client, String namespace, CloudEvents events, Clock clock) {
        this.client = client;
        this.namespace = namespace;
        this.events = events;
        this.clock = clock;
    }

    /** Appelé quand la messagerie est prête : buckets et abonnements. */
    public void attach(NatsMessaging m) throws Exception {
        m.ensureBucket(BridgeSubjects.SERVICES_BUCKET, SERVICES_TTL);
        m.ensureBucket(BridgeSubjects.PLAYERS_BUCKET, PLAYERS_TTL);
        m.connection().createDispatcher(msg -> onReport(Wire.fromJson(
                new String(msg.getData(), StandardCharsets.UTF_8), ServiceReport.class)))
                .subscribe(BridgeSubjects.reports(namespace));
        m.connection().createDispatcher(msg -> {
            String type = msg.getSubject().substring(msg.getSubject().lastIndexOf('.') + 1);
            onPlayerEvent(type, Wire.fromJson(new String(msg.getData(), StandardCharsets.UTF_8), PlayerInfo.class));
        }).subscribe(BridgeSubjects.playerEvents(namespace));
        messaging = m;
    }

    public ServiceReport report(String service) {
        return reports.get(service);
    }

    /** Publie (ou retire) l'entrée KV d'un service selon sa phase. */
    public void sync(CloudService svc) {
        NatsMessaging m = messaging;
        if (m == null) {
            return;
        }
        String name = svc.getMetadata().getName();
        CloudServiceStatus.Phase phase = svc.getStatus() == null ? null : svc.getStatus().phase;
        try {
            KeyValueApi kv = m.keyValue(BridgeSubjects.SERVICES_BUCKET);
            if (phase == CloudServiceStatus.Phase.Running || phase == CloudServiceStatus.Phase.Starting) {
                kv.put(name, Wire.toJson(ServiceInfos.of(svc, reports.get(name))).getBytes(StandardCharsets.UTF_8));
            } else {
                remove(name);
            }
        } catch (RuntimeException e) {
            LOG.debug("KV services indisponible : {}", e.getMessage());
        }
    }

    /** Retire un service du registre (arrêt, suppression). */
    public void remove(String service) {
        reports.remove(service);
        lastStatusPatch.remove(service);
        NatsMessaging m = messaging;
        if (m != null) {
            try {
                if (m.keyValue(BridgeSubjects.SERVICES_BUCKET).get(service) != null) {
                    m.keyValue(BridgeSubjects.SERVICES_BUCKET).delete(service);
                }
            } catch (RuntimeException e) {
                LOG.debug("KV services indisponible : {}", e.getMessage());
            }
        }
    }

    void onReport(ServiceReport r) {
        if (r == null || r.service == null) {
            return;
        }
        ServiceReport previous = reports.put(r.service, r);
        CloudService svc = client.resources(CloudService.class).inNamespace(namespace).withName(r.service).get();
        if (svc == null) {
            return;
        }
        if (r.frozen != null && (previous == null || previous.frozen == null)) {
            onFrozen(svc, r.frozen);
        }
        sync(svc);
        Instant now = clock.instant();
        Instant last = lastStatusPatch.get(r.service);
        boolean changed = previous == null || previous.onlinePlayers != r.onlinePlayers
                || !Objects.equals(previous.state, r.state) || !Objects.equals(previous.properties, r.properties)
                || !Objects.equals(previous.motd, r.motd) || previous.maxPlayers != r.maxPlayers;
        if (changed && (last == null || now.isAfter(last.plusSeconds(STATUS_MIN_INTERVAL_SECONDS)))) {
            patchStatus(r, now);
            lastStatusPatch.put(r.service, now);
        }
    }

    /** Proxy figé (chien de garde) : événement {@code service.frozen} et métrique. */
    private void onFrozen(CloudService svc, String reason) {
        LOG.warn("service {} figé : {}", svc.getMetadata().getName(), reason);
        io.ksmc.wire.messaging.CloudEvent e = io.ksmc.controller.messaging.Snapshots.service("service.frozen", svc);
        e.data.getAsJsonObject().addProperty("reason", reason);
        try {
            events.publish(e);
        } catch (RuntimeException ex) {
            LOG.debug("événement service.frozen non publié : {}", ex.getMessage());
        }
        java.util.function.Consumer<String> l = frozenListener;
        if (l != null) {
            l.accept(svc.getSpec().task);
        }
    }

    private volatile java.util.function.Consumer<String> frozenListener;

    /** Appelé avec la task d'un service à chaque gel signalé (métriques). */
    public void onFrozen(java.util.function.Consumer<String> listener) {
        frozenListener = listener;
    }

    private void patchStatus(ServiceReport r, Instant now) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("onlinePlayers", r.onlinePlayers);
        status.put("maxPlayers", r.maxPlayers);
        status.put("players", r.onlinePlayers + "/" + r.maxPlayers);
        status.put("motd", r.motd);
        status.put("state", r.state);
        status.put("tps", r.tps);
        status.put("properties", r.properties);
        if (r.onlinePlayers > 0) {
            status.put("lastPlayerAt", now.toString());
        }
        try {
            client.resources(CloudService.class).inNamespace(namespace).withName(r.service).subresource("status")
                    .patch(PatchContext.of(PatchType.JSON_MERGE), Wire.toJson(Map.of("status", status)));
        } catch (RuntimeException e) {
            LOG.debug("statut de {} non mis à jour : {}", r.service, e.getMessage());
        }
    }

    /** Recopie dans le statut (avant un patch du reconciler) les dernières valeurs remontées par le bridge. */
    public void overlay(String service, CloudServiceStatus status) {
        ServiceReport r = reports.get(service);
        if (r == null) {
            return;
        }
        status.onlinePlayers = r.onlinePlayers;
        status.maxPlayers = r.maxPlayers;
        status.motd = r.motd;
        status.state = r.state;
        status.tps = r.tps;
        status.properties = new LinkedHashMap<>(r.properties);
    }

    void onPlayerEvent(String type, PlayerInfo p) {
        if (p == null) {
            return;
        }
        String eventType = switch (type) {
            case "login" -> "player.login";
            case "switch" -> "player.server-switch";
            case "disconnect" -> "player.disconnect";
            default -> null;
        };
        if (eventType == null) {
            return;
        }
        CloudEvent e = CloudEvent.of(eventType, p.name);
        e.data = Snapshots.json(p);
        events.publish(e);
    }
}
