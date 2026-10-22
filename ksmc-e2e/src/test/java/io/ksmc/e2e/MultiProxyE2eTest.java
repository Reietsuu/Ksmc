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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class MultiProxyE2eTest {
    static final Duration TIMEOUT = Duration.ofMinutes(8);
    static final Duration START = Duration.ofMinutes(20);
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
                  bridge: {forwarding: legacy}
                  properties: {ksmc.online-mode: "false", ksmc.transfer.fallback-message: "e2e-reconnecte-toi"}
                  expose: {type: ClusterIP}
                """);
        // Lobby 1.8.8 (BuildTools, en cache pour la recette réseau) : le bot parle le protocole 1.8.
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2em-lobby, labels: {e2e: multiproxy}}
                spec:
                  environment: server
                  software: {name: spigot, version: "1.8.8"}
                  memoryMb: 768
                  minServiceCount: 1
                  bridge: {forwarding: legacy}
                  fallback: {enabled: true, priority: 10}
                """);
        await("deux proxies Running", TIMEOUT, () -> running("e2em-proxy-1") && running("e2em-proxy-2"));
        await("lobby Running", START, () -> running("e2em-lobby-1"));
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

    /** Raison de déconnexion du bot {@code name} entré par le Pod {@code pod}, ou {@code "en jeu"}. */
    static String joinVia(String pod, String name) throws Exception {
        try (LocalPortForward fwd = CLIENT.pods().inNamespace(NAMESPACE).withName(pod).portForward(25565);
                BotClient bot = BotClient.connect("127.0.0.1", fwd.getLocalPort(), name)) {
            await(name + " via " + pod, Duration.ofSeconds(60), () -> bot.disconnectReason() != null || bot.playing());
            return bot.playing() ? "en jeu" : bot.disconnectReason();
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

        // Session libérée : Bob entre.
        controller.keyValue(BridgeSubjects.PLAYERS_BUCKET).delete(held.uuid);
        assertThat(joinVia("e2em-proxy-2", "Bob")).isEqualTo("en jeu");
    }

    static void proxyTask(String name, String extra) {
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: %s, labels: {e2e: multiproxy}}
                spec:
                  environment: proxy
                  software: {name: velocity, version: latest}
                  memoryMb: 512
                  minServiceCount: 1
                  bridge: {forwarding: legacy}
                  expose: {type: NodePort, maxPlayers: 50}
                %s
                """.formatted(name, extra.indent(2)));
    }

    static int pingOnline(String service) {
        try (LocalPortForward fwd = CLIENT.services().inNamespace(NAMESPACE).withName(service).portForward(25565)) {
            return com.google.gson.JsonParser.parseString(ServerListPing.ping("127.0.0.1", fwd.getLocalPort()))
                    .getAsJsonObject().getAsJsonObject("players").get("online").getAsInt();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    void proxyTasksHaveIndependentSettingsAndShareServers() throws Exception {
        proxyTask("e2em-eu", "maintenance: true\nproperties: {ksmc.online-mode: \"false\", ksmc.maintenance.message: e2e-maintenance}");
        proxyTask("e2em-us", "properties: {ksmc.online-mode: \"false\", ksmc.player-count.scope: task}");
        await("proxies eu et us Running", TIMEOUT, () -> running("e2em-eu-1") && running("e2em-us-1"));
        await("lobby partagé enregistré", Duration.ofSeconds(60), () -> logs("e2em-us-1").contains("serveur ajouté : e2em-lobby-1")
                && logs("e2em-eu-1").contains("serveur ajouté : e2em-lobby-1"));

        // Une exposition par task, qui ne mène qu'à ses proxies.
        for (String t : new String[] {"e2em-eu", "e2em-us"}) {
            io.fabric8.kubernetes.api.model.Service svc = CLIENT.services().inNamespace(NAMESPACE).withName(t).get();
            assertThat(svc.getSpec().getType()).isEqualTo("NodePort");
            assertThat(svc.getSpec().getSelector()).containsEntry("ksmc.io/task", t);
        }

        // Maintenance de eu seulement.
        try (LocalPortForward eu = CLIENT.services().inNamespace(NAMESPACE).withName("e2em-eu").portForward(25565);
                BotClient refused = BotClient.connect("127.0.0.1", eu.getLocalPort(), "Frank")) {
            await("Frank refusé par eu", Duration.ofSeconds(60), () -> refused.disconnectReason() != null);
            assertThat(refused.disconnectReason()).contains("e2e-maintenance");
        }
        try (LocalPortForward us = CLIENT.services().inNamespace(NAMESPACE).withName("e2em-us").portForward(25565);
                BotClient grace = BotClient.connect("127.0.0.1", us.getLocalPort(), "Grace")) {
            await("Grace en jeu via us", Duration.ofSeconds(60), () -> {
                if (grace.disconnectReason() != null) {
                    throw new AssertionError(grace + BridgeNetworkingE2eTest.diagnostics("e2em-us-1"));
                }
                return grace.playing();
            });
            // Compteur limité à la task : us voit Grace (1 joueur) quels que soient les autres proxies.
            await("ping de us à 1 joueur", Duration.ofSeconds(30), () -> pingOnline("e2em-us") == 1);
            // Les sessions bloquent aussi entre tasks : Grace ne peut pas entrer en plus via e2em-proxy-1.
            assertThat(joinVia("e2em-proxy-1", "Grace")).contains("déjà connecté");
        }
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2em-eu").delete();
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2em-us").delete();
    }

    /** Dernier scénario : arrête e2em-proxy-2 (remplacé ensuite par la task). */
    @Test
    @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
    void stoppingProxyDisconnectsOldClientsWithFallbackMessage() throws Exception {
        try (LocalPortForward fwd = CLIENT.pods().inNamespace(NAMESPACE).withName("e2em-proxy-2").portForward(25565);
                BotClient bot = BotClient.connect("127.0.0.1", fwd.getLocalPort(), "Eve")) {
            await("Eve en jeu via e2em-proxy-2", Duration.ofSeconds(60), () -> {
                if (bot.disconnectReason() != null) {
                    throw new AssertionError(bot + BridgeNetworkingE2eTest.diagnostics("e2em-proxy-2"));
                }
                return bot.playing();
            });
            CLIENT.resources(io.ksmc.api.model.CloudService.class).inNamespace(NAMESPACE).withName("e2em-proxy-2").delete();
            await("Eve déconnectée", Duration.ofSeconds(60), () -> bot.disconnectReason() != null);
            assertThat(bot.disconnectReason()).contains("e2e-reconnecte-toi");
        }
    }
}
