package io.ksmc.controller.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.ServiceTask;
import org.junit.jupiter.api.Test;

class ProxyExposureTest {

    static ServiceTask task(String expose) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: proxy, namespace: prod}
                spec:
                  environment: proxy
                  software: {name: velocity}
                %s
                """.formatted(expose.indent(2)), ServiceTask.class);
    }

    @Test
    void defaultsToLoadBalancerOnMinecraftPort() {
        var svc = ProxyExposure.build(task(""));
        assertThat(svc.getSpec().getType()).isEqualTo("LoadBalancer");
        assertThat(svc.getSpec().getPorts().get(0).getPort()).isEqualTo(25565);
        assertThat(svc.getSpec().getPorts().get(0).getTargetPort().getStrVal()).isEqualTo("minecraft");
        assertThat(svc.getSpec().getSelector()).containsEntry("ksmc.io/task", "proxy").containsEntry("ksmc.io/environment", "proxy");
    }

    @Test
    void nodePortWithAnnotationsAndPolicies() {
        var svc = ProxyExposure.build(task("""
                expose:
                  type: NodePort
                  nodePort: 30565
                  annotations: {lb.example.com/ip: 1.2.3.4}
                  labels: {team: games}
                  externalTrafficPolicy: Local
                """));
        assertThat(svc.getSpec().getType()).isEqualTo("NodePort");
        assertThat(svc.getSpec().getPorts().get(0).getNodePort()).isEqualTo(30565);
        assertThat(svc.getSpec().getExternalTrafficPolicy()).isEqualTo("Local");
        assertThat(svc.getMetadata().getAnnotations()).containsEntry("lb.example.com/ip", "1.2.3.4");
        assertThat(svc.getMetadata().getLabels()).containsEntry("team", "games").containsEntry("ksmc.io/task", "proxy");
    }

    @Test
    void loadBalancerSourceRanges() {
        var svc = ProxyExposure.build(task("expose: {loadBalancerSourceRanges: [10.0.0.0/8]}"));
        assertThat(svc.getSpec().getLoadBalancerSourceRanges()).containsExactly("10.0.0.0/8");
    }
}
