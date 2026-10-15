package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.KeyValueApi;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scénarios du change p05 : bridges, enregistrement dynamique, exposition, joueurs. */
class BridgeNetworkingE2eTest {
    static final Duration TIMEOUT = Duration.ofMinutes(8);
    static LocalPortForward natsForward;
    static NatsMessaging controller;

    @BeforeAll
    static void connect() throws Exception {
        natsForward = CLIENT.services().inNamespace(NAMESPACE).withName("ksmc-nats").portForward(4222);
        controller = NatsMessaging.connect(Identity.controller(NAMESPACE), "nats://127.0.0.1:" + natsForward.getLocalPort(),
                "controller", secret("controller-password"));
    }

    @AfterAll
    static void disconnect() throws Exception {
        controller.close();
        natsForward.close();
    }

    @AfterEach
    void cleanup() {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "network").delete();
        await("services réseau supprimés", Duration.ofMinutes(3), () -> CLIENT.pods().inNamespace(NAMESPACE).list().getItems()
                .stream().noneMatch(p -> p.getMetadata().getName().startsWith("e2en")));
    }

    static String secret(String key) {
        return new String(Base64.getDecoder().decode(CLIENT.secrets().inNamespace(NAMESPACE).withName("ksmc-nats-auth")
                .get().getData().get(key)), StandardCharsets.UTF_8);
    }

    static void task(String yaml) {
        CLIENT.resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).inNamespace(NAMESPACE)
                .fieldManager("e2e").forceConflicts().serverSideApply();
    }

    static boolean running(String name) {
        CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(name).get();
        if (s != null && s.getStatus() != null && s.getStatus().phase == Phase.Failed) {
            throw new AssertionError(name + " failed: " + s.getStatus().reason + " " + s.getStatus().message);
        }
        return s != null && s.getStatus() != null && s.getStatus().phase == Phase.Running;
    }

    static String logs(String pod) {
        try {
            return CLIENT.pods().inNamespace(NAMESPACE).withName(pod).inContainer("server").getLog();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static ServiceInfo registry(String service) {
        KeyValueApi.Entry e = controller.keyValue(BridgeSubjects.SERVICES_BUCKET).get(service);
        return e == null ? null : Wire.fromJson(e.valueAsString(), ServiceInfo.class);
    }

    void lobbies(int count) {
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2en-lobby, labels: {e2e: network}}
                spec:
                  environment: server
                  software: {name: paper, version: "1.21.4"}
                  memoryMb: 1024
                  minServiceCount: %d
                  fallback: {enabled: true, priority: 10}
                """.formatted(count));
    }

    void proxy(String software) {
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2en-proxy, labels: {e2e: network}}
                spec:
                  environment: proxy
                  software: {name: %s, version: latest}
                  memoryMb: 512
                  minServiceCount: 1
                  properties: {ksmc.online-mode: "false"}
                  expose: {type: ClusterIP, maxPlayers: 500}
                """.formatted(software));
    }

    @Test
    void velocityNetworkRegistersServersDynamicallyAndAnswersGlobalPing() throws Exception {
        lobbies(1);
        proxy("velocity");
        await("proxy et lobby Running", TIMEOUT, () -> running("e2en-proxy-1") && running("e2en-lobby-1"));

        // Le bridge Velocity ajoute le lobby à sa liste de serveurs.
        await("lobby enregistré dans Velocity", Duration.ofSeconds(30),
                () -> logs("e2en-proxy-1").contains("serveur ajouté : e2en-lobby-1"));
        // Le bridge Paper remonte son état : le registre et le statut Kubernetes le reflètent.
        await("rapport du bridge Paper", Duration.ofSeconds(30), () -> {
            ServiceInfo info = registry("e2en-lobby-1");
            CloudService svc = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName("e2en-lobby-1").get();
            return info != null && info.fallback && info.maxPlayers > 0
                    && svc.getStatus().maxPlayers != null && svc.getStatus().maxPlayers > 0;
        });

        // Ping « liste des serveurs » à travers le Service Kubernetes du proxy.
        try (LocalPortForward fwd = CLIENT.services().inNamespace(NAMESPACE).withName("e2en-proxy").portForward(25565)) {
            JsonObject status = JsonParser.parseString(ServerListPing.ping("127.0.0.1", fwd.getLocalPort())).getAsJsonObject();
            assertThat(status.getAsJsonObject("players").get("max").getAsInt()).isEqualTo(500);
            assertThat(status.getAsJsonObject("players").get("online").getAsInt()).isZero();
        }

        // Nouveau serveur : visible par le proxy en moins de 5 s après son passage en Running.
        List<CloudEvent> ready = new CopyOnWriteArrayList<>();
        controller.events().subscribe(EventFilter.type("service.ready"), e -> {
            if (e.subject.equals("e2en-lobby-2")) {
                ready.add(e);
            }
        });
        lobbies(2);
        await("lobby-2 Running", TIMEOUT, () -> !ready.isEmpty());
        long readyAt = System.currentTimeMillis();
        await("lobby-2 enregistré", Duration.ofSeconds(5), () -> logs("e2en-proxy-1").contains("serveur ajouté : e2en-lobby-2"));
        assertThat(System.currentTimeMillis() - readyAt).isLessThan(5000);
    }

    @Test
    void playerEventsAreRelayedAsCloudEvents() throws Exception {
        List<CloudEvent> events = new CopyOnWriteArrayList<>();
        controller.events().subscribe(EventFilter.type("player.*"), events::add);
        PlayerInfo steve = new PlayerInfo();
        steve.uuid = "00000000-0000-0000-0000-000000000001";
        steve.name = "Steve";
        steve.proxy = "e2en-proxy-1";
        try (NatsMessaging proxyIdentity = NatsMessaging.connect(Identity.service(NAMESPACE, "e2en-proxy-1", "e2en-proxy", "proxy"),
                "nats://127.0.0.1:" + natsForward.getLocalPort(), "service", secret("service-password"))) {
            proxyIdentity.connection().publish(BridgeSubjects.playerEvent(NAMESPACE, "login"),
                    Wire.toJson(steve).getBytes(StandardCharsets.UTF_8));
            proxyIdentity.connection().flush(Duration.ofSeconds(2));
        }
        await("événement player.login", Duration.ofSeconds(10), () -> !events.isEmpty());
        assertThat(events.get(0).type).isEqualTo("player.login");
        assertThat(events.get(0).subject).isEqualTo("Steve");
    }

    @Test
    void bungeeCordRegistersServersToo() {
        lobbies(1);
        proxy("bungeecord");
        await("proxy BungeeCord et lobby Running", TIMEOUT, () -> running("e2en-proxy-1") && running("e2en-lobby-1"));
        await("lobby enregistré dans BungeeCord", Duration.ofSeconds(30),
                () -> logs("e2en-proxy-1").contains("serveur ajouté : e2en-lobby-1"));
    }
}
