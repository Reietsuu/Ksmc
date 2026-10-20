package io.ksmc.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.controller.task.ScalingStrategies;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class ServiceTaskReconcilerTest {
    KubernetesClient client;
    final java.util.List<io.ksmc.wire.messaging.CloudEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();
    MutableClock clock;
    ServiceTaskReconciler reconciler;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        reconciler = new ServiceTaskReconciler(client, new ScalingStrategies(), new EventRecorder(client), published::add,
                new io.ksmc.controller.bridge.BridgeHub(client, "test", published::add, clock), clock);
    }

    ServiceTask task(String name, String spec) {
        return client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: %s, namespace: test}
                spec:
                  environment: server
                  software: {name: paper}
                %s
                """.formatted(name, spec.indent(2)), ServiceTask.class)).create();
    }

    List<CloudService> services() {
        return client.resources(CloudService.class).inNamespace("test").list().getItems().stream()
                .sorted(java.util.Comparator.comparing(s -> s.getMetadata().getName())).toList();
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

    void setPhase(String name, Phase phase, Integer players) {
        CloudService svc = client.resources(CloudService.class).inNamespace("test").withName(name).get();
        svc.setStatus(new CloudServiceStatus());
        svc.getStatus().phase = phase;
        svc.getStatus().onlinePlayers = players;
        client.resource(svc).updateStatus();
    }

    @Test
    void createsMinimumServicesWithEffectiveSpec() {
        ServiceTask t = run(task("lobby", "minServiceCount: 2\nenv: {A: '1'}"));

        assertThat(names()).containsExactly("lobby-1", "lobby-2");
        CloudService svc = services().getFirst();
        assertThat(svc.getSpec().task).isEqualTo("lobby");
        assertThat(svc.getSpec().env).containsEntry("A", "1");
        assertThat(svc.getMetadata().getLabels()).containsEntry("ksmc.io/task", "lobby").containsEntry("ksmc.io/environment", "server");
        assertThat(svc.getMetadata().getAnnotations()).containsKey("ksmc.io/config-hash");
        assertThat(svc.getMetadata().getOwnerReferences().getFirst().getName()).isEqualTo("lobby");
        assertThat(t.getStatus().selector).isEqualTo("ksmc.io/task=lobby");
        assertThat(t.getStatus().ready).isEqualTo("0/2");
    }

    @Test
    void scaleDownStopsEmptiestHighestFirstAndReusesNumbers() {
        ServiceTask t = run(task("game", "minServiceCount: 3"));
        setPhase("game-1", Phase.Running, 5);
        setPhase("game-2", Phase.Running, 0);
        setPhase("game-3", Phase.Running, 0);

        client.resources(ServiceTask.class).inNamespace("test").withName("game").edit(x -> {
            x.getSpec().minServiceCount = 1;
            return x;
        });
        t = run(t);
        assertThat(names()).containsExactly("game-1");

        client.resources(CloudService.class).inNamespace("test").withName("game-1").delete();
        client.resources(ServiceTask.class).inNamespace("test").withName("game").edit(x -> {
            x.getSpec().minServiceCount = 2;
            return x;
        });
        run(t);
        assertThat(names()).containsExactly("game-1", "game-2");
    }

    @Test
    void readyCountAndPlayersAreAggregated() {
        ServiceTask t = run(task("lobby", "minServiceCount: 3"));
        setPhase("lobby-1", Phase.Running, 4);
        setPhase("lobby-2", Phase.Running, 6);
        setPhase("lobby-3", Phase.Starting, null);

        t = run(t);

        assertThat(t.getStatus().ready).isEqualTo("2/3");
        assertThat(t.getStatus().readyServices).isEqualTo(2);
        assertThat(t.getStatus().onlinePlayers).isEqualTo(10);
        assertThat(t.getStatus().phases).containsEntry("Running", 2).containsEntry("Starting", 1);
        assertThat(Conditions.isTrue(t.getStatus(), Conditions.READY)).isFalse();
    }

    @Test
    void failedServicesAreReplacedUntilCrashLoopBackoff() {
        ServiceTask t = run(task("crash", "minServiceCount: 1"));
        for (int i = 0; i < 5; i++) {
            setPhase(names().getFirst(), Phase.Failed, null);
            t = run(t);
        }
        assertThat(Conditions.isTrue(t.getStatus(), ServiceTaskReconciler.DEGRADED)).isTrue();
        assertThat(published).extracting(e -> e.type).contains("service.deleted", "task.degraded");
        assertThat(t.getStatus().failureWindow.failures).isEqualTo(5);
        assertThat(services()).isEmpty();

        clock.now = clock.now.plus(Duration.ofSeconds(25));
        t = run(t);
        assertThat(services()).hasSize(1);
    }

    @Test
    void disabledTaskStopsEverything() {
        ServiceTask t = run(task("lobby", "minServiceCount: 2"));
        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").edit(x -> {
            x.getSpec().enabled = false;
            return x;
        });
        run(t);
        assertThat(services()).isEmpty();
    }

    @Test
    void staticServicesAreStoppedAndRestartedNotDeleted() {
        ServiceTask t = run(task("survival", "minServiceCount: 2\nstatic: true"));
        assertThat(services()).allMatch(s -> s.getSpec().restartPolicy == CloudServiceSpec.RestartPolicy.Always);

        client.resources(ServiceTask.class).inNamespace("test").withName("survival").edit(x -> {
            x.getSpec().minServiceCount = 1;
            return x;
        });
        t = run(t);
        assertThat(names()).containsExactly("survival-1", "survival-2");
        assertThat(services().get(1).getSpec().desiredState).isEqualTo(CloudServiceSpec.DesiredState.Stopped);

        client.resources(ServiceTask.class).inNamespace("test").withName("survival").edit(x -> {
            x.getSpec().minServiceCount = 2;
            return x;
        });
        run(t);
        assertThat(services()).allMatch(s -> s.getSpec().desiredState == CloudServiceSpec.DesiredState.Running);
        assertThat(names()).containsExactly("survival-1", "survival-2");
    }

    @Test
    void startRequestsAnnotationIsCappedByMax() {
        ServiceTask t = task("game", "minServiceCount: 1\nmaxServiceCount: 3");
        client.resources(ServiceTask.class).inNamespace("test").withName("game").edit(x -> {
            x.getMetadata().getAnnotations().put("ksmc.io/start-requests", "5");
            return x;
        });

        run(t);

        ServiceTask stored = client.resources(ServiceTask.class).inNamespace("test").withName("game").get();
        assertThat(stored.getSpec().extraServices).isEqualTo(2);
        assertThat(stored.getMetadata().getAnnotations()).doesNotContainKey("ksmc.io/start-requests");
        assertThat(names()).hasSize(3);
    }

    @Test
    void rollingUpdateReplacesOneServiceAtATime() {
        ServiceTask t = run(task("lobby", "minServiceCount: 3"));
        List.of("lobby-1", "lobby-2", "lobby-3").forEach(n -> setPhase(n, Phase.Running, 0));
        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").edit(x -> {
            x.getSpec().memoryMb = 2048;
            return x;
        });

        t = run(t);

        List<CloudService> svcs = services();
        assertThat(svcs).hasSize(3);
        assertThat(svcs.stream().filter(s -> s.getSpec().memoryMb == 2048)).hasSize(1);
        assertThat(t.getStatus().conditions).anyMatch(c -> c.getType().equals("Progressing") && c.getReason().equals("Rolling"));
    }

    @Test
    void maintenanceIsAppliedInPlaceWithoutReplacingServices() {
        ServiceTask t = run(task("lobby", "minServiceCount: 2"));
        List.of("lobby-1", "lobby-2").forEach(n -> setPhase(n, Phase.Running, 0));
        java.util.Set<String> uids = new java.util.HashSet<>();
        services().forEach(s -> uids.add(s.getMetadata().getUid()));
        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").edit(x -> {
            x.getSpec().maintenance = true;
            return x;
        });

        t = run(t);

        List<CloudService> svcs = services();
        assertThat(svcs).hasSize(2).allMatch(s -> uids.contains(s.getMetadata().getUid()))
                .allMatch(s -> "true".equals(s.getSpec().properties.get("ksmc.maintenance")));
        assertThat(t.getStatus().conditions).noneMatch(c -> "Rolling".equals(c.getReason()));

        client.resources(ServiceTask.class).inNamespace("test").withName("lobby").edit(x -> {
            x.getSpec().maintenance = false;
            return x;
        });
        run(t);
        assertThat(services()).hasSize(2).allMatch(s -> uids.contains(s.getMetadata().getUid()))
                .noneMatch(s -> "true".equals(s.getSpec().properties.get("ksmc.maintenance")));
    }

    @Test
    void missingGroupBlocksCreation() {
        ServiceTask t = run(task("lobby", "groups: [absent]"));

        assertThat(services()).isEmpty();
        assertThat(t.getStatus().conditions).anyMatch(c -> c.getReason().equals("GroupNotFound"));

        client.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceGroup
                metadata: {name: absent, namespace: test}
                spec: {env: {X: y}}
                """, ServiceGroup.class)).create();
        t = run(t);
        assertThat(services()).hasSize(1);
        assertThat(services().getFirst().getSpec().env).containsEntry("X", "y");
        assertThat(t.getStatus().effective.groups).containsExactly("absent");
    }
}
