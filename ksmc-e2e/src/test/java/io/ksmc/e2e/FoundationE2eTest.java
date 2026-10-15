package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.EXAMPLES;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.ServiceTask;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Scénarios du change p01-foundation (specs resource-model et deployment). */
class FoundationE2eTest {

    @AfterEach
    void cleanup() {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "foundation").delete();
    }

    @Test
    void crdsAreInstalledWithShortNames() {
        Map<String, List<String>> shortNames = CLIENT.apiextensions().v1().customResourceDefinitions().list().getItems()
                .stream()
                .filter(crd -> crd.getSpec().getGroup().equals("ksmc.io"))
                .collect(Collectors.toMap(c -> c.getSpec().getNames().getKind(), c -> c.getSpec().getNames().getShortNames()));

        assertThat(shortNames).containsOnlyKeys("ServiceTask", "ServiceGroup", "ServiceTemplate",
                "CloudService", "SoftwareDefinition", "KsmcModule");
        assertThat(shortNames.values()).flatExtracting(l -> l)
                .containsExactlyInAnyOrder("mctask", "mcgroup", "mctpl", "mcsvc", "mcsw", "mcmod");
        assertThat(CLIENT.apiextensions().v1().customResourceDefinitions().withName("servicetasks.ksmc.io").get())
                .extracting(CustomResourceDefinition::getSpec)
                .satisfies(s -> assertThat(s.getScope()).isEqualTo("Namespaced"));
    }

    @Test
    void invalidTaskIsRejected() {
        assertThatThrownBy(() -> create("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: bad-count, labels: {e2e: foundation}}
                spec: {environment: server, software: {name: paper}, minServiceCount: -2}
                """)).isInstanceOf(KubernetesClientException.class).hasMessageContaining("minServiceCount");

        assertThatThrownBy(() -> create("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: bad-env, labels: {e2e: foundation}}
                spec: {environment: database, software: {name: paper}}
                """)).isInstanceOf(KubernetesClientException.class).hasMessageContaining("environment");

        assertThatThrownBy(() -> create("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: bad-max, labels: {e2e: foundation}}
                spec: {environment: server, software: {name: paper}, minServiceCount: 3, maxServiceCount: 2}
                """)).isInstanceOf(KubernetesClientException.class).hasMessageContaining("maxServiceCount");
    }

    @Test
    void kubectlScaleChangesMinServiceCount() {
        create("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: scaled, labels: {e2e: foundation}}
                spec: {environment: server, software: {name: paper}, minServiceCount: 1}
                """);

        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("scaled").scale(3);

        assertThat(CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("scaled").get()
                .getSpec().minServiceCount).isEqualTo(3);
    }

    @Test
    void extensionsArePreserved() {
        create("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: extended, labels: {e2e: foundation}}
                spec:
                  environment: server
                  software: {name: paper}
                  extensions: {mymodule: {foo: bar}}
                """);

        ServiceTask task = CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("extended").get();
        assertThat(task.getSpec().extensions.get("mymodule").get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void allExamplesAreAcceptedByTheApiServer() throws Exception {
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            assertThat(yamls).isNotEmpty();
            for (Path file : yamls) {
                try (InputStream in = Files.newInputStream(file)) {
                    List<HasMetadata> items = CLIENT.load(in).items();
                    assertThat(items).as(file.toString()).isNotEmpty();
                    for (HasMetadata item : items) {
                        CLIENT.resource(item).inNamespace(NAMESPACE).dryRun().create();
                    }
                }
            }
        }
    }

    @Test
    void controllerIsReady() {
        Deployment controller = CLIENT.apps().deployments().inNamespace(NAMESPACE)
                .withLabel("app.kubernetes.io/component", "controller").list().getItems().getFirst();
        assertThat(controller.getStatus().getReadyReplicas()).isEqualTo(1);
    }

    private static void create(String yaml) {
        // Ressource générique : la validation doit venir de l'API Kubernetes, pas du client.
        CLIENT.resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).inNamespace(NAMESPACE).create();
    }
}
