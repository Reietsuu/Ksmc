package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Ksmc;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class LastLogTest {
    KubernetesClient client;
    KubernetesMockServer server;

    CloudService svc(boolean inTask) {
        CloudService svc = new CloudService();
        ObjectMetaBuilder meta = new ObjectMetaBuilder().withName("game-3").withNamespace("test").withUid("svc-uid");
        if (inTask) {
            meta.addToLabels(Ksmc.LABEL_TASK, "game").withOwnerReferences(new OwnerReferenceBuilder()
                    .withApiVersion("ksmc.io/v1alpha1").withKind("ServiceTask").withName("game").withUid("task-uid")
                    .withController(true).build());
        }
        svc.setMetadata(meta.build());
        svc.setSpec(new CloudServiceSpec());
        return svc;
    }

    @Test
    void failureExtractIsKeptAndOwnedByTheTask() {
        server.expect().get().withPath("/api/v1/namespaces/test/pods/game-3/log?pretty=false&container=server&tailLines=500")
                .andReturn(200, "Exception in thread main\njava.lang.OutOfMemoryError").always();

        LastLog.capture(client, svc(true), "game-3", "OOMKilled", Instant.parse("2026-10-19T12:00:00Z"));

        ConfigMap cm = client.configMaps().inNamespace("test").withName("game-3-lastlog").get();
        assertThat(cm).as("ConfigMap créé (chemin des logs attendu par la simulation)").isNotNull();
        assertThat(cm.getData()).containsEntry("reason", "OOMKilled").containsEntry("capturedAt", "2026-10-19T12:00:00Z");
        assertThat(cm.getData().get("log")).contains("OutOfMemoryError");
        assertThat(cm.getMetadata().getOwnerReferences()).singleElement().satisfies(o -> {
            assertThat(o.getKind()).isEqualTo("ServiceTask");
            assertThat(o.getController()).isFalse();
        });
        assertThat(cm.getMetadata().getLabels()).containsEntry(Ksmc.LABEL_TASK, "game");

        // Nouvel échec : l'extrait est remplacé.
        LastLog.capture(client, svc(true), "game-3", "Crashed", Instant.parse("2026-10-19T13:00:00Z"));
        assertThat(client.configMaps().inNamespace("test").withName("game-3-lastlog").get().getData())
                .containsEntry("reason", "Crashed");
    }

    @Test
    void standaloneServiceOwnsItsExtractAndErrorsAreSwallowed() {
        server.expect().get().withPath("/api/v1/namespaces/test/pods/game-3/log?pretty=false&container=server&tailLines=500")
                .andReturn(200, "boom").always();
        LastLog.capture(client, svc(false), "game-3", "Crashed", Instant.now());
        assertThat(client.configMaps().inNamespace("test").withName("game-3-lastlog").get().getMetadata()
                .getOwnerReferences()).singleElement().satisfies(o -> assertThat(o.getKind()).isEqualTo("CloudService"));

        // Pod sans logs : rien n'est levé.
        LastLog.capture(client, svc(false), "absent", "Crashed", Instant.now());
    }
}
