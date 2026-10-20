package io.ksmc.e2e;

import static io.ksmc.e2e.BridgeNetworkingE2eTest.diagnostics;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.logs;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.running;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.task;
import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Recette « réseau complet » (p12) : proxies Velocity et BungeeCord, lobby Paper, task Spigot 1.8.8 (BuildTools),
 * serveurs Fabric et NeoForge ; un joueur bot (protocole 1.8) entre par chaque proxy, atterrit sur le fallback,
 * change de serveur via l'API puis est expulsé.
 */
@Order(Integer.MAX_VALUE - 1)
class NetworkAcceptanceE2eTest {

    static final Duration START = Duration.ofMinutes(20);
    static final List<String> SERVERS = List.of("e2ea-lobby-1", "e2ea-legacy-1", "e2ea-legacy-2", "e2ea-fabric-1",
            "e2ea-neoforge-1");
    static ApiClient api;

    static void server(String name, String software, String version, int memory, int count, String extra) {
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: %s, labels: {e2e: acceptance}}
                spec:
                  environment: server
                  software: {name: %s, version: "%s"}
                  memoryMb: %d
                  minServiceCount: %d
                  bridge: {forwarding: legacy}
                %s
                """.formatted(name, software, version, memory, count, extra.indent(2)));
    }

    static void proxy(String name, String software) {
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: %s, labels: {e2e: acceptance}}
                spec:
                  environment: proxy
                  software: {name: %s, version: latest}
                  memoryMb: 512
                  minServiceCount: 1
                  bridge: {forwarding: legacy}
                  properties: {ksmc.online-mode: "false"}
                  expose: {type: ClusterIP, maxPlayers: 100}
                """.formatted(name, software));
    }

    @BeforeAll
    static void network() {
        api = new ApiClient();
        api.admin();
        proxy("e2ea-velocity", "velocity");
        proxy("e2ea-bungee", "bungeecord");
        server("e2ea-lobby", "paper", "latest", 1024, 1, "fallback: {enabled: true, priority: 10}");
        // Serveur legacy : fallback prioritaire, c'est là qu'arrive le bot (client 1.8).
        server("e2ea-legacy", "spigot", "1.8.8", 768, 2, "fallback: {enabled: true, priority: 100}");
        server("e2ea-fabric", "fabric", "latest", 1024, 1, "");
        server("e2ea-neoforge", "neoforge", "latest", 2048, 1, "");
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "acceptance").delete();
        await("services de recette supprimés", Duration.ofMinutes(3), () -> CLIENT.pods().inNamespace(NAMESPACE)
                .list().getItems().stream().noneMatch(p -> p.getMetadata().getName().startsWith("e2ea-")));
        api.close();
    }

    static void awaitRunning(String... services) {
        for (String s : services) {
            try {
                await(s + " Running", START, () -> running(s));
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + diagnostics(s), e);
            }
        }
    }

    /** Joueur dans le registre (GET /api/v1/players/{nom}), ou null. */
    static JsonNode player(String name) {
        HttpResponse<String> r = api.call("GET", "/api/v1/players/" + name, api.admin(), null);
        return r.statusCode() == 200 ? ApiClient.json(r) : null;
    }

    static void journey(String proxy, String bot) throws Exception {
        try (LocalPortForward fwd = CLIENT.services().inNamespace(NAMESPACE).withName(proxy).portForward(25565);
                BotClient client = BotClient.connect("127.0.0.1", fwd.getLocalPort(), bot)) {
            await(bot + " en jeu via " + proxy, Duration.ofSeconds(60), () -> {
                if (client.disconnectReason() != null) {
                    throw new AssertionError(client + diagnostics(proxy + "-1"));
                }
                return client.playing();
            });
            await(bot + " dans le registre", Duration.ofSeconds(30), () -> player(bot) != null
                    && player(bot).path("server").asText().startsWith("e2ea-legacy-"));
            JsonNode p = player(bot);
            assertThat(p.get("proxy").asText()).isEqualTo(proxy + "-1");
            String from = p.get("server").asText();
            String to = from.equals("e2ea-legacy-1") ? "e2ea-legacy-2" : "e2ea-legacy-1";

            HttpResponse<String> send = api.call("POST", "/api/v1/players/" + bot + "/send", api.admin(),
                    Map.of("service", to));
            assertThat(send.statusCode()).as(send.body()).isLessThan(300);
            await(bot + " envoyé sur " + to, Duration.ofSeconds(30), () -> player(bot) != null
                    && to.equals(player(bot).path("server").asText()));
            assertThat(client.playing()).as(client.toString()).isTrue();

            HttpResponse<String> kick = api.call("POST", "/api/v1/players/" + bot + "/kick", api.admin(),
                    Map.of("reason", "recette-ok"));
            assertThat(kick.statusCode()).as(kick.body()).isLessThan(300);
            await(bot + " expulsé", Duration.ofSeconds(30), () -> client.disconnectReason() != null);
            assertThat(client.disconnectReason()).contains("recette-ok");
            await(bot + " sorti du registre", Duration.ofSeconds(30), () -> player(bot) == null);
        }
    }

    @Test
    void fullNetworkWithBotPlayer() throws Exception {
        awaitRunning("e2ea-velocity-1", "e2ea-bungee-1");
        awaitRunning(SERVERS.toArray(String[]::new));

        // Chaque proxy connaît tous les serveurs de jeu.
        for (String proxy : List.of("e2ea-velocity-1", "e2ea-bungee-1")) {
            for (String server : SERVERS) {
                await(server + " enregistré dans " + proxy, Duration.ofSeconds(60),
                        () -> logs(proxy).contains("serveur ajouté : " + server));
            }
        }
        // Les bridges des serveurs moddés remontent leur état (registre des services).
        for (String modded : List.of("e2ea-fabric-1", "e2ea-neoforge-1")) {
            await("rapport du bridge de " + modded, Duration.ofSeconds(60), () -> {
                CloudService svc = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(modded).get();
                return svc.getStatus().maxPlayers != null && svc.getStatus().maxPlayers > 0;
            });
        }

        journey("e2ea-velocity", "BotVelocity");
        journey("e2ea-bungee", "BotBungee");
    }
}
