package io.ksmc.controller.task;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Kubernetes exposant une task proxy hors du cluster. Il porte le nom de la task et ne répartit
 * les connexions que sur les proxies prêts (sonde de readiness).
 */
public final class ProxyExposure {
    private ProxyExposure() {
    }

    public static boolean applies(ServiceTask task) {
        return task.getSpec().environment == Environment.PROXY;
    }

    public static Service build(ServiceTask task) {
        ServiceTaskSpec.Expose expose = task.getSpec().expose == null ? new ServiceTaskSpec.Expose() : task.getSpec().expose;
        String name = task.getMetadata().getName();
        Map<String, String> labels = new LinkedHashMap<>(expose.labels);
        labels.put(Ksmc.LABEL_TASK, name);
        labels.put(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY);
        ServicePortBuilder port = new ServicePortBuilder().withName("minecraft").withProtocol("TCP")
                .withPort(expose.port).withNewTargetPort("minecraft");
        if (expose.type == ServiceTaskSpec.Expose.Type.NodePort && expose.nodePort != null) {
            port.withNodePort(expose.nodePort);
        }
        ServiceBuilder svc = new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(task.getMetadata().getNamespace())
                    .withLabels(labels).withAnnotations(new LinkedHashMap<>(expose.annotations)).endMetadata()
                .withNewSpec()
                    .withType(expose.type.name())
                    .withSelector(Map.of(Ksmc.LABEL_TASK, name, Ksmc.LABEL_ENVIRONMENT, "proxy"))
                    .withPorts(port.build())
                .endSpec();
        if (expose.type != ServiceTaskSpec.Expose.Type.ClusterIP) {
            if (expose.externalTrafficPolicy != null) {
                svc.editSpec().withExternalTrafficPolicy(expose.externalTrafficPolicy).endSpec();
            }
        }
        if (expose.type == ServiceTaskSpec.Expose.Type.LoadBalancer && !expose.loadBalancerSourceRanges.isEmpty()) {
            svc.editSpec().withLoadBalancerSourceRanges(expose.loadBalancerSourceRanges).endSpec();
        }
        return svc.build();
    }
}
