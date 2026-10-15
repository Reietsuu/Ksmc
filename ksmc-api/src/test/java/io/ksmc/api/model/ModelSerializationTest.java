package io.ksmc.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

class ModelSerializationTest {

    private static final String TASK = """
            apiVersion: ksmc.io/v1alpha1
            kind: ServiceTask
            metadata:
              name: lobby
            spec:
              environment: server
              software: {name: paper, version: 1.21.4}
              minServiceCount: 2
              static: true
              groups: [global]
              fallback: {enabled: true, priority: 10}
              extensions:
                mymodule: {foo: bar, nested: [1, 2]}
            """;

    @Test
    void readsTaskYaml() {
        ServiceTask task = Serialization.unmarshal(TASK, ServiceTask.class);

        assertThat(task.getSpec().environment).isEqualTo(Environment.SERVER);
        assertThat(task.getSpec().software.name).isEqualTo("paper");
        assertThat(task.getSpec().staticServices).isTrue();
        assertThat(task.getSpec().minServiceCount).isEqualTo(2);
        assertThat(task.getSpec().maxServiceCount).isEqualTo(-1);
        assertThat(task.getSpec().memoryMb).isEqualTo(1024);
        assertThat(task.getSpec().fallback.priority).isEqualTo(10);
    }

    @Test
    void preservesExtensionsUnchanged() {
        ServiceTask task = Serialization.unmarshal(TASK, ServiceTask.class);
        String yaml = Serialization.asYaml(task);
        ServiceTask again = Serialization.unmarshal(yaml, ServiceTask.class);

        assertThat(again.getSpec().extensions.get("mymodule").get("foo").asText()).isEqualTo("bar");
        assertThat(again.getSpec().extensions.get("mymodule").get("nested").size()).isEqualTo(2);
    }

    @Test
    void writesLowercaseEnvironmentAndStaticKeyword() {
        ServiceTask task = Serialization.unmarshal(TASK, ServiceTask.class);
        String yaml = Serialization.asYaml(task);

        assertThat(yaml).contains("environment: \"server\"").contains("static: true");
    }

    @Test
    void cloudServiceStatusRoundTrip() {
        CloudService svc = new CloudService();
        svc.setSpec(new CloudServiceSpec());
        svc.getSpec().software = new SoftwareRef("velocity", "latest");
        svc.getSpec().environment = Environment.PROXY;
        svc.setStatus(new CloudServiceStatus());
        svc.getStatus().phase = CloudServiceStatus.Phase.Running;
        svc.getStatus().properties.put("map", "Desert");

        CloudService again = Serialization.unmarshal(Serialization.asJson(svc), CloudService.class);

        assertThat(again.getStatus().phase).isEqualTo(CloudServiceStatus.Phase.Running);
        assertThat(again.getStatus().properties).containsEntry("map", "Desert");
        assertThat(again.getSpec().desiredState).isEqualTo(CloudServiceSpec.DesiredState.Running);
    }

    @Test
    void allResourcesHaveExpectedApiVersion() {
        assertThat(Ksmc.RESOURCE_TYPES).hasSize(6);
        assertThat(new ServiceTask().getApiVersion()).isEqualTo("ksmc.io/v1alpha1");
        assertThat(new KsmcModule().getKind()).isEqualTo("KsmcModule");
    }
}
