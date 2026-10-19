package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Scénario du change p07. Les « bots » sont simulés au niveau que lit l'autoscaling : des rapports de joueurs publiés
 * sur NATS avec l'identité du service, exactement comme le bridge (bridge désactivé sur la task pour ne pas les
 * écraser) ; le comptage des joueurs par le bridge lui-même est couvert par les scénarios p05.
 */
class AutoscalingE2eTest {

    static final Duration TIMEOUT = Duration.ofMinutes(6);
    static LocalPortForward natsForward;
    static NatsMessaging controller;
    static final ScheduledExecutorService BOTS = Executors.newSingleThreadScheduledExecutor();
    /** Joueurs simulés par service (rapport renvoyé toutes les 2 s). */
    static final Map<String, Integer> PLAYERS = new ConcurrentHashMap<>();

    @BeforeAll
    static void connect() throws Exception {
        natsForward = CLIENT.services().inNamespace(NAMESPACE).withName("ksmc-nats").portForward(4222);
        String url = "nats://127.0.0.1:" + natsForward.getLocalPort();
        controller = NatsMessaging.connect(Identity.controller(NAMESPACE), url, "controller", secret("controller-password"));
        NatsMessaging service = NatsMessaging.connect(Identity.service(NAMESPACE, "e2ea-bots", "e2ea-game", "server"), url,
                "service", secret("service-password"));
        BOTS.scheduleAtFixedRate(() -> PLAYERS.forEach((svc, online) -> {
            ServiceReport r = new ServiceReport();
            r.service = svc;
            r.onlinePlayers = online;
            r.maxPlayers = 20;
            r.motd = "bots";
            service.connection().publish(BridgeSubjects.report(NAMESPACE, svc), Wire.toJson(r).getBytes(StandardCharsets.UTF_8));
        }), 0, 2, TimeUnit.SECONDS);
    }

    @AfterAll
    static void cleanup() throws Exception {
        BOTS.shutdownNow();
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "autoscaling").delete();
        controller.close();
        natsForward.close();
    }

    static String secret(String key) {
        return new String(Base64.getDecoder().decode(CLIENT.secrets().inNamespace(NAMESPACE).withName("ksmc-nats-auth")
                .get().getData().get(key)), StandardCharsets.UTF_8);
    }

    static List<CloudService> services() {
        return CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withLabel("ksmc.io/task", "e2ea-game").list()
                .getItems().stream().filter(s -> s.getMetadata().getDeletionTimestamp() == null).toList();
    }

    static boolean running(String name) {
        CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(name).get();
        if (s != null && s.getStatus() != null && s.getStatus().phase == CloudServiceStatus.Phase.Failed) {
            throw new AssertionError(name + " failed: " + s.getStatus().reason + " " + s.getStatus().message);
        }
        return s != null && s.getStatus() != null && s.getStatus().phase == CloudServiceStatus.Phase.Running;
    }

    static boolean taskEvent(String reason) {
        return CLIENT.v1().events().inNamespace(NAMESPACE).list().getItems().stream()
                .anyMatch(e -> reason.equals(e.getReason()) && "e2ea-game".equals(e.getInvolvedObject().getName()));
    }

    @Test
    void playersStartAServiceAndItStopsAfterIdle() {
        List<CloudEvent> scaled = new CopyOnWriteArrayList<>();
        controller.events().subscribe(EventFilter.type("task.scaled"), e -> {
            if ("e2ea-game".equals(e.subject)) {
                scaled.add(e);
            }
        });
        CLIENT.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2ea-game, labels: {e2e: autoscaling}}
                spec:
                  environment: server
                  software: {name: paper, version: "1.21.4"}
                  memoryMb: 1024
                  minServiceCount: 1
                  maxServiceCount: 2
                  bridge: {enabled: false}
                  autoscaling:
                    strategies: [players]
                    scaleUpThreshold: 0.5
                    idleStopSeconds: 20
                    cooldownSeconds: 10
                """, GenericKubernetesResource.class)).inNamespace(NAMESPACE).fieldManager("e2e").forceConflicts()
                .serverSideApply();
        await("e2ea-game-1 Running", TIMEOUT, () -> running("e2ea-game-1"));

        // Les bots remplissent le premier service (15/20 = 75 % > 50 %) : un deuxième démarre.
        PLAYERS.put("e2ea-game-1", 15);
        await("deuxième service créé", Duration.ofMinutes(1), () -> services().size() == 2);
        await("événement ScaledUp", Duration.ofSeconds(30), () -> taskEvent("ScaledUp"));
        await("task.scaled publié", Duration.ofSeconds(30), () -> !scaled.isEmpty());
        assertThat(scaled.getFirst().data.getAsJsonObject().get("to").getAsInt()).isEqualTo(2);
        ServiceTask task = CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2ea-game").get();
        assertThat(task.getStatus().autoscaling.lastDecision).isEqualTo("ScaledUp");
        assertThat(task.getStatus().autoscaling.observedFill).isEqualTo(0.75);
        await("e2ea-game-2 Running", TIMEOUT, () -> running("e2ea-game-2"));

        // Les bots partent : après 20 s à vide (et le cooldown), retour au minimum d'un service.
        PLAYERS.put("e2ea-game-1", 0);
        PLAYERS.put("e2ea-game-2", 0);
        await("retour à un service", Duration.ofMinutes(2), () -> services().size() == 1);
        await("événement ScaledDown", Duration.ofSeconds(30), () -> taskEvent("ScaledDown"));
        task = CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2ea-game").get();
        assertThat(task.getStatus().autoscaling.lastDecision).isEqualTo("ScaledDown");
    }
}
