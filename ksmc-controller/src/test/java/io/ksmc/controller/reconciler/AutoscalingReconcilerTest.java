package io.ksmc.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.controller.bridge.BridgeHub;
import io.ksmc.controller.task.ScalingStrategies;
import io.ksmc.wire.messaging.CloudEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class AutoscalingReconcilerTest {
    KubernetesClient client;
    final List<CloudEvent> published = new CopyOnWriteArrayList<>();
    ServiceTaskReconcilerTest.MutableClock clock;
    ServiceTaskReconciler reconciler;

    @BeforeEach
    void setUp() {
        clock = new ServiceTaskReconcilerTest.MutableClock();
        reconciler = new ServiceTaskReconciler(client, new ScalingStrategies(), new EventRecorder(client), published::add,
                new BridgeHub(client, "test", published::add, clock), clock);
    }

    ServiceTask task(String spec) {
        return client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: game, namespace: test}
                spec:
                  environment: server
                  software: {name: paper}
                %s
                """.formatted(spec.indent(2)), ServiceTask.class)).create();
    }

    List<CloudService> services() {
        return client.resources(CloudService.class).inNamespace("test").list().getItems().stream()
                .sorted(Comparator.comparing(s -> s.getMetadata().getName())).toList();
    }

    List<String> names() {
        return services().stream().map(s -> s.getMetadata().getName()).toList();
    }

    ServiceTask run(ServiceTask task) {
        ServiceTask fresh = client.resource(task).get();
        if (task.getStatus() != null) {
            fresh.setStatus(task.getStatus());
        }
        return reconciler.reconcile(fresh, services()).getResource().orElseThrow();
    }

    void players(String name, int online, int max) {
        CloudService svc = client.resources(CloudService.class).inNamespace("test").withName(name).get();
        svc.setStatus(new CloudServiceStatus());
        svc.getStatus().phase = Phase.Running;
        svc.getStatus().onlinePlayers = online;
        svc.getStatus().maxPlayers = max;
        client.resource(svc).updateStatus();
    }

    List<Event> events(String reason) {
        return client.v1().events().inNamespace("test").list().getItems().stream()
                .filter(e -> reason.equals(e.getReason())).toList();
    }

    static final String PLAYERS = """
            minServiceCount: 2
            maxServiceCount: 5
            autoscaling: {strategies: [players], scaleUpThreshold: 0.8, idleStopSeconds: 300, cooldownSeconds: 60}
            """;

    @Test
    void fullServicesTriggerAScaleUpThatIsRecordedAndPublished() {
        ServiceTask t = run(task(PLAYERS));
        assertThat(names()).containsExactly("game-1", "game-2");
        players("game-1", 17, 20);
        players("game-2", 17, 20);

        t = run(t);

        assertThat(names()).containsExactly("game-1", "game-2", "game-3");
        assertThat(t.getStatus().autoscaling.lastDecision).isEqualTo("ScaledUp");
        assertThat(t.getStatus().autoscaling.desiredServices).isEqualTo(3);
        assertThat(t.getStatus().autoscaling.observedFill).isEqualTo(0.85);
        assertThat(events("ScaledUp")).singleElement()
                .satisfies(e -> assertThat(e.getMessage()).isEqualTo("2 → 3 : remplissage 85 % > seuil 80 %"));
        assertThat(published).filteredOn(e -> e.type.equals("task.scaled")).singleElement().satisfies(e -> {
            assertThat(e.subject).isEqualTo("game");
            assertThat(e.data.getAsJsonObject().get("from").getAsInt()).isEqualTo(2);
            assertThat(e.data.getAsJsonObject().get("to").getAsInt()).isEqualTo(3);
        });

        // Le nouveau service démarre encore : pas de deuxième démarrage simultané.
        t = run(t);
        assertThat(names()).hasSize(3);
    }

    @Test
    void briefPeakStartsOneServiceAndEmptyServicesStopAfterIdleOnly() {
        ServiceTask t = run(task(PLAYERS));
        players("game-1", 17, 20);
        players("game-2", 17, 20);
        t = run(t);
        players("game-3", 0, 20);

        // Le pic retombe 10 s plus tard : rien n'est arrêté (cooldown, puis idle pas atteint).
        clock.now = clock.now.plusSeconds(10);
        players("game-1", 0, 20);
        players("game-2", 3, 20);
        t = run(t);
        assertThat(names()).hasSize(3);
        assertThat(t.getStatus().autoscaling.emptySince).containsOnlyKeys("game-1", "game-3");

        clock.now = clock.now.plusSeconds(120);
        t = run(t);
        assertThat(names()).hasSize(3);

        // Vides depuis plus de 300 s : arrêt, mais jamais sous le minimum (2), le service occupé reste ; à égalité de
        // durée à vide, le plus grand numéro part d'abord.
        clock.now = clock.now.plusSeconds(200);
        t = run(t);
        assertThat(names()).containsExactly("game-1", "game-2");
        assertThat(t.getStatus().autoscaling.lastDecision).isEqualTo("ScaledDown");
        assertThat(events("ScaledDown")).singleElement()
                .satisfies(e -> assertThat(e.getMessage()).startsWith("3 → 2 : 2 service(s) vide(s)"));
    }

    @Test
    void preparedServiceIsPromotedAndReplaced() {
        ServiceTask t = run(task("""
                minServiceCount: 1
                autoscaling: {strategies: [players], preparedServices: 1}
                """));
        assertThat(names()).containsExactly("game-1", "game-2");
        assertThat(services().get(1).getMetadata().getLabels()).containsEntry(Ksmc.LABEL_PREPARED, "true");
        assertThat(services().get(0).getMetadata().getLabels()).doesNotContainKey(Ksmc.LABEL_PREPARED);

        players("game-1", 19, 20);
        players("game-2", 0, 20);
        t = run(t);

        CloudService promoted = client.resources(CloudService.class).inNamespace("test").withName("game-2").get();
        assertThat(promoted.getMetadata().getLabels()).doesNotContainKey(Ksmc.LABEL_PREPARED);
        CloudService replacement = client.resources(CloudService.class).inNamespace("test").withName("game-3").get();
        assertThat(replacement.getMetadata().getLabels()).containsEntry(Ksmc.LABEL_PREPARED, "true");
        assertThat(events("ServicePromoted")).hasSize(1);
        // Le service préparé vide n'a jamais été compté comme « vide » pour l'arrêt.
        assertThat(t.getStatus().autoscaling.emptySince).doesNotContainKey("game-3");
    }

    @Test
    void scheduledMinimumIsAppliedAtOnceAndReleasedAfterwards() {
        clock.now = ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, ZoneId.of("Europe/Paris")).toInstant();
        ServiceTask t = run(task("""
                minServiceCount: 2
                autoscaling:
                  strategies: [schedule, players]
                  idleStopSeconds: 60
                  schedule:
                    - {from: "0 18 * * *", to: "0 23 * * *", timeZone: Europe/Paris, minServiceCount: 5}
                """));
        assertThat(names()).hasSize(5);
        assertThat(events("ScaledUp")).singleElement()
                .satisfies(e -> assertThat(e.getMessage()).isEqualTo("0 → 5 : minimum programmé de 5 service(s)"));

        clock.now = ZonedDateTime.of(2026, 10, 19, 23, 1, 0, 0, ZoneId.of("Europe/Paris")).toInstant();
        names().forEach(n -> players(n, 0, 20));
        t = run(t);
        assertThat(names()).as("idle pas encore atteint").hasSize(5);
        clock.now = clock.now.plusSeconds(120);
        t = run(t);
        assertThat(names()).hasSize(2);
    }

    @Test
    void disablingAutoscalingPromotesPreparedServices() {
        ServiceTask t = run(task("""
                minServiceCount: 1
                autoscaling: {strategies: [players], preparedServices: 1}
                """));
        client.resources(ServiceTask.class).inNamespace("test").withName("game").edit(x -> {
            x.getSpec().autoscaling = null;
            x.getSpec().minServiceCount = 2;
            return x;
        });
        t = run(t);

        assertThat(names()).containsExactly("game-1", "game-2");
        assertThat(services()).allSatisfy(s -> assertThat(s.getMetadata().getLabels()).doesNotContainKey(Ksmc.LABEL_PREPARED));
        assertThat(t.getStatus().autoscaling).isNull();
    }
}
