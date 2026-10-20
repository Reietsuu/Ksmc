package io.ksmc.controller.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectiveConfigTest {

    static ServiceGroup group(String name, String spec) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceGroup
                metadata: {name: %s}
                spec:
                %s
                """.formatted(name, spec.indent(2)), ServiceGroup.class);
    }

    static ServiceTask task(String spec) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: lobby}
                spec:
                  environment: server
                  software: {name: paper}
                %s
                """.formatted(spec.indent(2)), ServiceTask.class);
    }

    final Map<String, ServiceGroup> groups = EffectiveConfig.index(List.of(
            group("all-servers", """
                    targetEnvironments: [server]
                    templates: [{name: global}]
                    jvmOptions: ["-XX:+UseG1GC"]
                    env: {ENV_A: "0", TZ: UTC}
                    podTemplate: {spec: {nodeSelector: {pool: games}}}
                    """),
            group("all-proxies", "targetEnvironments: [proxy]\ntemplates: [{name: proxy-only}]"),
            group("plugins", """
                    templates: [{name: plugins}]
                    env: {ENV_A: "1"}
                    properties: {region: eu}
                    podTemplate: {spec: {tolerations: [{key: games, operator: Exists}]}}
                    """)));

    @Test
    void appliesEnvironmentGroupsThenReferencedGroupsThenTask() throws Exception {
        EffectiveConfig cfg = EffectiveConfig.resolve(task("""
                groups: [plugins]
                templates: [{name: lobby}]
                jvmOptions: ["-Dtask=1"]
                env: {ENV_A: "2"}
                """), groups);

        assertThat(cfg.groups()).containsExactly("all-servers", "plugins");
        assertThat(cfg.templateNames()).containsExactly("global", "plugins", "lobby");
        CloudServiceSpec s = cfg.serviceSpec();
        assertThat(s.jvmOptions).containsExactly("-XX:+UseG1GC", "-Dtask=1");
        assertThat(s.env).containsEntry("ENV_A", "2").containsEntry("TZ", "UTC");
        assertThat(s.properties).containsEntry("region", "eu");
        assertThat(s.podTemplate.at("/spec/nodeSelector/pool").asText()).isEqualTo("games");
        assertThat(s.podTemplate.at("/spec/tolerations/0/key").asText()).isEqualTo("games");
        assertThat(s.task).isEqualTo("lobby");
        assertThat(s.restartPolicy).isEqualTo(CloudServiceSpec.RestartPolicy.Never);
    }

    @Test
    void missingGroupIsReported() {
        assertThatThrownBy(() -> EffectiveConfig.resolve(task("groups: [absent]"), groups))
                .isInstanceOf(EffectiveConfig.GroupNotFoundException.class).hasMessageContaining("absent");
    }

    @Test
    void hashChangesWithConfigurationOnly() throws Exception {
        String a = EffectiveConfig.resolve(task("minServiceCount: 2"), groups).hash();
        String b = EffectiveConfig.resolve(task("minServiceCount: 5"), groups).hash();
        String c = EffectiveConfig.resolve(task("minServiceCount: 2\nmemoryMb: 2048"), groups).hash();

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        // Maintenance : réglage à chaud, hors du hash (pas de mise à jour progressive), mais présent dans la spec.
        EffectiveConfig m = EffectiveConfig.resolve(task("minServiceCount: 2\nmaintenance: true"), groups);
        assertThat(m.hash()).isEqualTo(a);
        assertThat(m.serviceSpec().properties).containsEntry("ksmc.maintenance", "true");
    }

    @Test
    void staticTasksRestartTheirServices() throws Exception {
        assertThat(EffectiveConfig.resolve(task("static: true"), groups).serviceSpec().restartPolicy)
                .isEqualTo(CloudServiceSpec.RestartPolicy.Always);
    }
}
