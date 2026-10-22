package io.ksmc.e2e;

import static io.ksmc.e2e.BridgeNetworkingE2eTest.logs;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.running;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.secret;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.task;
import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.LocalPortForward;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.messaging.Identity;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scénarios du change p13 : réseau à plusieurs proxies Velocity. */
class MultiProxyE2eTest {
    static final Duration TIMEOUT = Duration.ofMinutes(8);
    static LocalPortForward natsForward;
    static NatsMessaging controller;

    @BeforeAll
    static void network() throws Exception {
        natsForward = CLIENT.services().inNamespace(NAMESPACE).withName("ksmc-nats").portForward(4222);
        controller = NatsMessaging.connect(Identity.controller(NAMESPACE), "nats://127.0.0.1:" + natsForward.getLocalPort(),
                "controller", secret("controller-password"));
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2em-proxy, labels: {e2e: multiproxy}}
                spec:
                  environment: proxy
                  software: {name: velocity, version: latest}
                  memoryMb: 512
                  minServiceCount: 2
                  properties: {ksmc.online-mode: "false"}
                  expose: {type: ClusterIP}
                """);
        await("deux proxies Running", TIMEOUT, () -> running("e2em-proxy-1") && running("e2em-proxy-2"));
        await("bridges connectés", Duration.ofSeconds(60), () -> logs("e2em-proxy-1").contains("connecté au cloud")
                && logs("e2em-proxy-2").contains("connecté au cloud"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "multiproxy").delete();
        await("proxies supprimés", Duration.ofMinutes(3), () -> CLIENT.pods().inNamespace(NAMESPACE).list().getItems()
                .stream().noneMatch(p -> p.getMetadata().getName().startsWith("e2em-")));
        controller.close();
        natsForward.close();
    }

    /** UUID d'un joueur hors ligne (identique sur tous les proxies). */
    static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Raison de déconnexion du bot {@code name} entré par le Pod {@code pod}. */
    static String joinVia(String pod, String name) throws Exception {
        try (LocalPortForward fwd = CLIENT.pods().inNamespace(NAMESPACE).withName(pod).portForward(25565);
                BotClient bot = BotClient.connect("127.0.0.1", fwd.getLocalPort(), name)) {
            await(name + " déconnecté par " + pod, Duration.ofSeconds(30), () -> bot.disconnectReason() != null);
            return bot.disconnectReason();
        }
    }

    static void hold(String name, String proxy, String server) {
        PlayerInfo p = new PlayerInfo();
        p.uuid = offlineUuid(name);
        p.name = name;
        p.proxy = proxy;
        p.server = server;
        p.connectedAt = System.currentTimeMillis();
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).put(p.uuid, Wire.toJson(p).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void glistOnOneProxyCountsPlayersOfAllProxies() throws Exception {
        hold("Carol", "e2em-proxy-1", "e2em-lobby-1");
        hold("Dave", "e2em-proxy-2", "e2em-lobby-1");
        try (ApiClient api = new ApiClient()) {
            HttpResponse<String> r = api.call("POST", "/api/v1/services/e2em-proxy-1/command", api.admin(),
                    Map.of("command", "glist"));
            assertThat(r.statusCode()).as(r.body()).isLessThan(300);
        }
        await("réponse de /glist", Duration.ofSeconds(30),
                () -> logs("e2em-proxy-1").contains("2 joueur(s) en ligne sur le réseau"));
        assertThat(logs("e2em-proxy-1")).contains("e2em-lobby-1 (2) : Carol, Dave");
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).delete(offlineUuid("Carol"));
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).delete(offlineUuid("Dave"));
    }

    @Test
    void secondSessionOfAPlayerIsRefused() throws Exception {
        // Session de Bob tenue par e2em-proxy-1 (telle que l'écrit son bridge à la connexion).
        PlayerInfo held = new PlayerInfo();
        held.uuid = offlineUuid("Bob");
        held.name = "Bob";
        held.proxy = "e2em-proxy-1";
        held.connectedAt = System.currentTimeMillis();
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).put(held.uuid, Wire.toJson(held).getBytes(StandardCharsets.UTF_8));

        assertThat(joinVia("e2em-proxy-2", "Bob")).contains("déjà connecté");

        // Session libérée : la connexion passe le contrôle (puis échoue faute de lobby, ce qui est attendu ici).
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).delete(held.uuid);
        assertThat(joinVia("e2em-proxy-2", "Bob")).doesNotContain("déjà connecté");
    }
}
