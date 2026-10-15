package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static io.ksmc.e2e.E2eSupport.exec;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scénarios du change p03-task-orchestration. Les tasks utilisent Velocity (démarrage rapide, peu de mémoire)
 * pour garder la suite légère ; la logique d'orchestration est indépendante du logiciel.
 */
class TaskOrchestrationE2eTest {
    static final Duration TIMEOUT = Duration.ofMinutes(6);

    @AfterEach
    void cleanup() {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "tasks").delete();
        CLIENT.resources(ServiceGroup.class).inNamespace(NAMESPACE).withLabel("e2e", "tasks").delete();
        await("services des tasks e2e supprimés", Duration.ofMinutes(3), () -> services("e2et").isEmpty()
                && CLIENT.pods().inNamespace(NAMESPACE).list().getItems().stream()
                        .noneMatch(p -> p.getMetadata().getName().startsWith("e2et")));
        CLIENT.persistentVolumeClaims().inNamespace(NAMESPACE).list().getItems().stream()
                .filter(p -> p.getMetadata().getName().startsWith("e2et"))
                .forEach(p -> CLIENT.resource(p).delete());
    }

    static void apply(String yaml) {
        CLIENT.resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).inNamespace(NAMESPACE)
                .serverSideApply();
    }

    static void task(String name, String spec) {
        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: %s, labels: {e2e: tasks}}
                spec:
                  environment: proxy
                  software: {name: velocity, version: latest}
                  memoryMb: 256
                %s
                """.formatted(name, spec.indent(2)));
    }

    static List<CloudService> services(String prefix) {
        return CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).list().getItems().stream()
                .filter(s -> s.getMetadata().getName().startsWith(prefix))
                .sorted(java.util.Comparator.comparing(s -> s.getMetadata().getName())).toList();
    }

    static List<String> runningNames(String task) {
        return services(task + "-").stream()
                .filter(s -> s.getStatus() != null && s.getStatus().phase == Phase.Running)
                .map(s -> s.getMetadata().getName()).toList();
    }

    static ServiceTask get(String name) {
        return CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName(name).get();
    }

    @Test
    void minimumIsMaintainedScaledAndNumbersReused() {
        task("e2et-lobby", "minServiceCount: 2");
        await("2 services Running", TIMEOUT, () -> runningNames("e2et-lobby").size() == 2);
        assertThat(runningNames("e2et-lobby")).containsExactly("e2et-lobby-1", "e2et-lobby-2");
        await("statut READY 2/2", Duration.ofMinutes(1), () -> "2/2".equals(get("e2et-lobby").getStatus().ready));

        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2et-lobby").scale(1);
        await("1 service après kubectl scale", TIMEOUT, () -> services("e2et-lobby-").size() == 1);

        // Le service restant est supprimé : il est remplacé sous le plus petit numéro libre.
        String remaining = services("e2et-lobby-").getFirst().getMetadata().getName();
        CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(remaining).delete();
        await("remplaçant e2et-lobby-1 Running", TIMEOUT, () -> runningNames("e2et-lobby").equals(List.of("e2et-lobby-1")));
    }

    @Test
    void staticTaskKeepsDataAcrossRestart() {
        task("e2et-static", "minServiceCount: 1\nstatic: true\nstorage: {size: 1Gi}");
        await("statique Running", TIMEOUT, () -> runningNames("e2et-static").size() == 1);
        exec("e2et-static-1", "echo persisted > /server/marker.txt");

        CLIENT.pods().inNamespace(NAMESPACE).withName("e2et-static-1").delete();
        await("pod redémarré", TIMEOUT, () -> {
            var pod = CLIENT.pods().inNamespace(NAMESPACE).withName("e2et-static-1").get();
            return pod != null && pod.getMetadata().getDeletionTimestamp() == null
                    && runningNames("e2et-static").size() == 1;
        });

        assertThat(exec("e2et-static-1", "cat /server/marker.txt")).contains("persisted");
    }

    @Test
    void rollingUpdateAppliesNewConfiguration() {
        task("e2et-roll", "minServiceCount: 2");
        await("2 services Running", TIMEOUT, () -> runningNames("e2et-roll").size() == 2);

        task("e2et-roll", "minServiceCount: 2\nenv: {KSMC_E2E: rolled}");

        await("tous les services mis à jour", TIMEOUT, () -> {
            List<CloudService> svcs = services("e2et-roll-");
            return svcs.size() == 2 && svcs.stream().allMatch(s -> "rolled".equals(s.getSpec().env.get("KSMC_E2E")))
                    && runningNames("e2et-roll").size() == 2;
        });
    }

    @Test
    void missingGroupBlocksThenGroupIsApplied() {
        task("e2et-grp", "minServiceCount: 1\ngroups: [e2et-group]");
        await("condition GroupNotFound", Duration.ofMinutes(1), () -> {
            ServiceTask t = get("e2et-grp");
            return t.getStatus() != null && t.getStatus().conditions.stream()
                    .anyMatch(c -> "GroupNotFound".equals(c.getReason()));
        });
        assertThat(services("e2et-grp-")).isEmpty();

        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceGroup
                metadata: {name: e2et-group, labels: {e2e: tasks}}
                spec: {env: {FROM_GROUP: "yes"}}
                """);

        await("service créé avec la config du groupe", TIMEOUT, () -> {
            List<CloudService> svcs = services("e2et-grp-");
            return svcs.size() == 1 && "yes".equals(svcs.getFirst().getSpec().env.get("FROM_GROUP"));
        });
    }
}
