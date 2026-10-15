package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static io.ksmc.e2e.E2eSupport.exec;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Scénarios du change p02-service-runtime : un CloudService devient un vrai serveur Minecraft. */
class ServiceRuntimeE2eTest {
    static final Duration START_TIMEOUT = Duration.ofMinutes(8);

    @AfterEach
    void cleanup() {
        CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withLabel("e2e", "runtime").delete();
        CLIENT.persistentVolumeClaims().inNamespace(NAMESPACE).withName("e2e-static-data").delete();
        await("services e2e supprimés", Duration.ofMinutes(2), () -> CLIENT.pods().inNamespace(NAMESPACE)
                .withLabel("ksmc.io/environment").list().getItems().stream()
                .noneMatch(p -> p.getMetadata().getName().startsWith("e2e-")));
    }

    static CloudService create(String name, String spec) {
        return CLIENT.resource(Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: CloudService
                metadata: {name: %s, labels: {e2e: runtime}}
                spec:
                %s
                """.formatted(name, spec.indent(2)), CloudService.class)).inNamespace(NAMESPACE).create();
    }

    static CloudServiceStatus status(String name) {
        CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(name).get();
        return s == null || s.getStatus() == null ? new CloudServiceStatus() : s.getStatus();
    }

    static void awaitPhase(String name, Phase phase, Duration timeout) {
        await(name + " → " + phase, timeout, () -> {
            CloudServiceStatus st = status(name);
            if (st.phase == Phase.Failed && phase != Phase.Failed) {
                throw new AssertionError(name + " failed: " + st.reason + " " + st.message);
            }
            return st.phase == phase;
        });
    }

    @Test
    void paperLatestReachesRunning() {
        create("e2e-paper", "environment: server\nsoftware: {name: paper, version: latest}\nmemoryMb: 1024");

        awaitPhase("e2e-paper", Phase.Running, START_TIMEOUT);

        CloudServiceStatus st = status("e2e-paper");
        assertThat(st.address).endsWith(":25565");
        assertThat(st.softwareVersion).isNotBlank();
        assertThat(exec("e2e-paper", "cat /server/server.properties")).contains("online-mode=false");
    }

    @Test
    void velocityReachesRunning() {
        create("e2e-velocity", "environment: proxy\nsoftware: {name: velocity, version: latest}\nmemoryMb: 512");

        awaitPhase("e2e-velocity", Phase.Running, START_TIMEOUT);

        assertThat(exec("e2e-velocity", "cat /server/velocity.toml")).contains("player-info-forwarding-mode = \"modern\"");
    }

    @Test
    void staticServiceKeepsFilesAcrossPodRecreation() {
        create("e2e-static", "environment: server\nsoftware: {name: paper, version: '1.21.4'}\nmemoryMb: 1024\nstatic: true");
        awaitPhase("e2e-static", Phase.Running, START_TIMEOUT);
        exec("e2e-static", "echo keep-me > /server/marker.txt");

        CLIENT.pods().inNamespace(NAMESPACE).withName("e2e-static").delete();
        await("pod recréé", Duration.ofMinutes(3), () -> {
            var pod = CLIENT.pods().inNamespace(NAMESPACE).withName("e2e-static").get();
            return pod != null && pod.getMetadata().getDeletionTimestamp() == null;
        });
        awaitPhase("e2e-static", Phase.Running, START_TIMEOUT);

        assertThat(exec("e2e-static", "cat /server/marker.txt")).contains("keep-me");
    }

    @Test
    void unknownVersionFails() {
        create("e2e-badversion", "environment: server\nsoftware: {name: paper, version: '0.0.1'}");

        awaitPhase("e2e-badversion", Phase.Failed, Duration.ofMinutes(2));

        assertThat(status("e2e-badversion").reason).isEqualTo("SoftwareResolutionFailed");
    }
}
