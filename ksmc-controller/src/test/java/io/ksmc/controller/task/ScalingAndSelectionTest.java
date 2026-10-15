package io.ksmc.controller.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.spi.ScalingStrategy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScalingAndSelectionTest {

    static ServiceTask task(String spec) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: game}
                spec:
                  environment: server
                  software: {name: paper}
                %s
                """.formatted(spec.indent(2)), ServiceTask.class);
    }

    static CloudService svc(String name, CloudServiceStatus.Phase phase, Integer players) {
        CloudService s = new CloudService();
        s.getMetadata().setName(name);
        s.setStatus(new CloudServiceStatus());
        s.getStatus().phase = phase;
        s.getStatus().onlinePlayers = players;
        return s;
    }

    int target(String spec) {
        return new ScalingStrategies().target(new ScalingStrategy.Context(task(spec), List.of(), Instant.now()));
    }

    @Test
    void staticTargetIsMinPlusManualBoundedByMax() {
        assertThat(target("minServiceCount: 2")).isEqualTo(2);
        assertThat(target("minServiceCount: 2\nextraServices: 3")).isEqualTo(5);
        assertThat(target("minServiceCount: 2\nextraServices: 3\nmaxServiceCount: 4")).isEqualTo(4);
        assertThat(target("minServiceCount: 0")).isZero();
    }

    @Test
    void moduleStrategiesCombineWithMaximum() {
        ScalingStrategies registry = new ScalingStrategies();
        registry.register(new ScalingStrategy() {
            public String name() {
                return "always-seven";
            }

            public int desiredServices(Context c) {
                return 7;
            }
        });
        var ctx = new ScalingStrategy.Context(task("minServiceCount: 1\nmaxServiceCount: 5\n"
                + "autoscaling: {strategies: [static, always-seven, unknown]}"), List.of(), Instant.now());
        assertThat(registry.target(ctx)).isEqualTo(5);
    }

    @Test
    void nextNamesReuseLowestFreeNumbers() {
        assertThat(ServiceSelection.nextNames("lobby", List.of("lobby-2", "lobby-4"), 3))
                .containsExactly("lobby-1", "lobby-3", "lobby-5");
        assertThat(ServiceSelection.nextNames("lobby", List.of("lobbyx-1", "lobby-abc"), 1)).containsExactly("lobby-1");
    }

    @Test
    void stopsNonRunningThenEmptiestThenHighestNumber() {
        var services = List.of(
                svc("game-1", CloudServiceStatus.Phase.Running, 8),
                svc("game-2", CloudServiceStatus.Phase.Running, 3),
                svc("game-3", CloudServiceStatus.Phase.Running, 3),
                svc("game-4", CloudServiceStatus.Phase.Starting, null));

        assertThat(ServiceSelection.toStop("game", services, 3)).extracting(s -> s.getMetadata().getName())
                .containsExactly("game-4", "game-3", "game-2");
    }
}
