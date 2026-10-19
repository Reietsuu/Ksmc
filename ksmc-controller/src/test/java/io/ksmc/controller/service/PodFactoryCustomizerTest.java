package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.spi.ServicePodCustomizer;
import io.ksmc.controller.ControllerConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PodFactoryCustomizerTest {

    @Test
    void customizersAddLabelsButCannotBreakIdentityAndFailuresAreIgnored() {
        ServicePodCustomizer label = (svc, pod) -> {
            pod.getMetadata().getLabels().put("team", "games");
            pod.getMetadata().getLabels().put("ksmc.io/service", "hijack");
            pod.getMetadata().setName("other");
        };
        ServicePodCustomizer broken = (svc, pod) -> {
            throw new IllegalStateException("boom");
        };
        PodFactory factory = new PodFactory(ControllerConfig.fromEnv(Map.of("KSMC_NAMESPACE", "test")),
                () -> List.of(broken, label));
        Pod pod = new PodBuilder().withNewMetadata().withName("lobby-1").withNamespace("test")
                .addToLabels("ksmc.io/service", "lobby-1").endMetadata().build();

        Pod out = factory.customize(new CloudService(), pod);

        assertThat(out.getMetadata().getName()).isEqualTo("lobby-1");
        assertThat(out.getMetadata().getLabels()).containsEntry("team", "games").containsEntry("ksmc.io/service", "lobby-1");
    }
}
