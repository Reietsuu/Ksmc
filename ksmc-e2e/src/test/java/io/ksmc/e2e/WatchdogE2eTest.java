package io.ksmc.e2e;

import static io.ksmc.e2e.BridgeNetworkingE2eTest.logs;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.running;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.secret;
import static io.ksmc.e2e.BridgeNetworkingE2eTest.task;
import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scénarios du change p14 : chien de garde d'un proxy Velocity figé. */
class WatchdogE2eTest {
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
                metadata: {name: e2ew-proxy, labels: {e2e: watchdog}}
                spec:
                  environment: proxy
                  software: {name: velocity, version: latest}
                  memoryMb: 512
                  minServiceCount: 2
                  bridge: {forwarding: legacy}
                  properties:
                    ksmc.online-mode: "false"
                    ksmc.watchdog.test: "true"
                    ksmc.watchdog.timeout-seconds: "10"
                    ksmc.transfer.fallback-message: e2e-proxy-fige
                  expose: {type: ClusterIP}
                """);
        task("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2ew-lobby, labels: {e2e: watchdog}}
                spec:
                  environment: server
                  software: {name: spigot, version: "1.8.8"}
                  memoryMb: 768
                  minServiceCount: 1
                  bridge: {forwarding: legacy}
                  fallback: {enabled: true, priority: 10}
                """);
        await("deux proxies Running", TIMEOUT, () -> running("e2ew-proxy-1") && running("e2ew-proxy-2"));
        await("lobby Running", START, () -> running("e2ew-lobby-1"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "watchdog").delete();
        await("services supprimés", Duration.ofMinutes(3), () -> CLIENT.pods().inNamespace(NAMESPACE).list().getItems()
                .stream().noneMatch(p -> p.getMetadata().getName().startsWith("e2ew-")));
        controller.close();
        natsForward.close();
    }

    static boolean podReady(String name) {
        Pod pod = CLIENT.pods().inNamespace(NAMESPACE).withName(name).get();
        return pod != null && pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /** UID des services Running de la task proxy. */
    static List<String> runningProxyUids() {
        return CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withLabel("ksmc.io/task", "e2ew-proxy")
                .list().getItems().stream()
                .filter(s -> s.getStatus() != null && s.getStatus().phase == io.ksmc.api.model.CloudServiceStatus.Phase.Running)
                .map(s -> s.getMetadata().getUid()).toList();
    }

    @Test
    void frozenProxyLeavesTheServiceDrainsItsPlayersAndRestarts() throws Exception {
        List<CloudEvent> frozen = new CopyOnWriteArrayList<>();
        controller.events().subscribe(EventFilter.type("service.frozen"), e -> {
            if (e.subject.equals("e2ew-proxy-2")) {
                frozen.add(e);
            }
        });
        String frozenUid = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName("e2ew-proxy-2").get()
                .getMetadata().getUid();
        try (LocalPortForward fwd = CLIENT.pods().inNamespace(NAMESPACE).withName("e2ew-proxy-2").portForward(25565);
                BotClient bot = BotClient.connect("127.0.0.1", fwd.getLocalPort(), "Ivy")) {
            await("Ivy en jeu via e2ew-proxy-2", Duration.ofSeconds(60), () -> {
                if (bot.disconnectReason() != null) {
                    throw new AssertionError(bot + BridgeNetworkingE2eTest.diagnostics("e2ew-proxy-2"));
                }
                return bot.playing();
            });
            try (ApiClient api = new ApiClient()) {
                HttpResponse<String> r = api.call("POST", "/api/v1/services/e2ew-proxy-2/command", api.admin(),
                        Map.of("command", "ksmc freeze-test"));
                assertThat(r.statusCode()).as(r.body()).isLessThan(300);
            }
            await("gel détecté", Duration.ofSeconds(60), () -> !frozen.isEmpty());
            assertThat(frozen.get(0).data.getAsJsonObject().get("reason").getAsString()).contains("sans réponse");
            await("e2ew-proxy-2 hors du Service", Duration.ofSeconds(30), () -> !podReady("e2ew-proxy-2"));
            await("Ivy déconnectée", Duration.ofSeconds(60), () -> bot.disconnectReason() != null);
            assertThat(bot.disconnectReason()).contains("e2e-proxy-fige");
        }
        // Service d'une task : le proxy arrêté (code 70) est remplacé par un nouveau service.
        await("proxy figé remplacé", Duration.ofMinutes(5), () -> {
            List<String> uids = runningProxyUids();
            return uids.size() == 2 && !uids.contains(frozenUid);
        });
        assertThat(logs("e2ew-proxy-1")).doesNotContain("proxy figé");
    }
}
