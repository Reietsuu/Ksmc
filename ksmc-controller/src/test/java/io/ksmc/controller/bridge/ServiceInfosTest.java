package io.ksmc.controller.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.controller.task.EffectiveConfig;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceInfosTest {

    @Test
    void taskRoutingSettingsReachTheBridgesThroughServiceProperties() throws Exception {
        ServiceTask task = Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: lobby}
                spec:
                  environment: server
                  software: {name: paper}
                  maintenance: true
                  fallback: {enabled: true, priority: 10, permission: lobby.join}
                """, ServiceTask.class);
        CloudService svc = new CloudService();
        svc.getMetadata().setName("lobby-1");
        svc.setSpec(EffectiveConfig.resolve(task, Map.of()).serviceSpec());
        svc.setStatus(Serialization.unmarshal("phase: Running\naddress: '10.0.0.5:25565'\nonlinePlayers: 1\n",
                io.ksmc.api.model.CloudServiceStatus.class));

        ServiceInfo info = ServiceInfos.of(svc, null);

        assertThat(info.fallback).isTrue();
        assertThat(info.fallbackPriority).isEqualTo(10);
        assertThat(info.fallbackPermission).isEqualTo("lobby.join");
        assertThat(info.maintenance).isTrue();
        assertThat(info.host).isEqualTo("10.0.0.5");
        assertThat(info.port).isEqualTo(25565);
        assertThat(info.isRunning()).isTrue();

        ServiceReport report = new ServiceReport();
        report.onlinePlayers = 12;
        report.maxPlayers = 50;
        report.state = "LOBBY";
        report.properties.put("map", "Desert");
        ServiceInfo withReport = ServiceInfos.of(svc, report);
        assertThat(withReport.onlinePlayers).isEqualTo(12);
        assertThat(withReport.state).isEqualTo("LOBBY");
        assertThat(withReport.properties).containsEntry("map", "Desert");
    }

    @Test
    void proxyForcedHostsAreLowercased() throws Exception {
        ServiceTask task = Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: proxy}
                spec:
                  environment: proxy
                  software: {name: velocity}
                  forcedHosts: {BedWars.Example.com: bedwars}
                """, ServiceTask.class);
        assertThat(EffectiveConfig.resolve(task, Map.of()).serviceSpec().properties)
                .containsEntry("ksmc.forced-host.bedwars.example.com", "bedwars");
    }
}
