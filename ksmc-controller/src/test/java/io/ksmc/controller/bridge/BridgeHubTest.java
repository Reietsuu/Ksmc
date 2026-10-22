package io.ksmc.controller.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.messaging.CloudEvent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BridgeHubTest {
    KubernetesClient client;

    @Test
    void frozenReportPublishesOneEventAndCountsIt() {
        CloudService svc = new CloudService();
        svc.setMetadata(new ObjectMetaBuilder().withName("proxy-1").withNamespace("prod").build());
        svc.setSpec(new CloudServiceSpec());
        svc.getSpec().task = "proxy";
        client.resource(svc).create();
        List<CloudEvent> events = new ArrayList<>();
        List<String> frozenTasks = new ArrayList<>();
        BridgeHub hub = new BridgeHub(client, "prod", events::add, Clock.systemUTC());
        hub.onFrozen(frozenTasks::add);

        ServiceReport r = new ServiceReport();
        r.service = "proxy-1";
        hub.onReport(r);
        ServiceReport frozen = new ServiceReport();
        frozen.service = "proxy-1";
        frozen.frozen = "bus d'événements sans réponse depuis 31 s";
        hub.onReport(frozen);
        hub.onReport(frozen);

        assertThat(events).extracting(e -> e.type).containsExactly("service.frozen");
        assertThat(events.get(0).subject).isEqualTo("proxy-1");
        assertThat(events.get(0).data.getAsJsonObject().get("reason").getAsString()).contains("31 s");
        assertThat(frozenTasks).containsExactly("proxy");
    }
}
