package io.ksmc.controller.task;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskStatus;
import java.time.Instant;

/** Fabrique de tasks et de services pour les tests de l'autoscaling. */
final class AutoscalingTestSupport {

    private AutoscalingTestSupport() {
    }

    static ServiceTask task(String specYaml) {
        ServiceTask t = Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: game, namespace: test}
                spec:
                  environment: server
                  software: {name: paper, version: latest}
                %s
                """.formatted(specYaml.indent(2)), ServiceTask.class);
        t.setStatus(new ServiceTaskStatus());
        t.getStatus().autoscaling = new ServiceTaskStatus.AutoscalingState();
        return t;
    }

    static CloudService svc(String name, CloudServiceStatus.Phase phase, int players, int max) {
        CloudService s = new CloudService();
        s.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("test").addToLabels(Ksmc.LABEL_TASK, "game")
                .build());
        s.setSpec(new CloudServiceSpec());
        s.setStatus(new CloudServiceStatus());
        s.getStatus().phase = phase;
        s.getStatus().onlinePlayers = players;
        s.getStatus().maxPlayers = max;
        return s;
    }

    static CloudService running(String name, int players, int max) {
        return svc(name, CloudServiceStatus.Phase.Running, players, max);
    }

    static CloudService prepared(CloudService s) {
        s.getMetadata().getLabels().put(Ksmc.LABEL_PREPARED, "true");
        return s;
    }

    static void emptySince(ServiceTask t, String service, Instant since) {
        t.getStatus().autoscaling.emptySince.put(service, since.toString());
    }
}
