package io.ksmc.controller.task;

import static io.ksmc.controller.task.AutoscalingTestSupport.emptySince;
import static io.ksmc.controller.task.AutoscalingTestSupport.prepared;
import static io.ksmc.controller.task.AutoscalingTestSupport.running;
import static io.ksmc.controller.task.AutoscalingTestSupport.svc;
import static io.ksmc.controller.task.AutoscalingTestSupport.task;
import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.spi.ScalingStrategy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayersScalingStrategyTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    final PlayersScalingStrategy players = new PlayersScalingStrategy();

    int desired(ServiceTask t, List<io.ksmc.api.model.CloudService> services) {
        return players.desiredServices(new ScalingStrategy.Context(t, services, NOW));
    }

    @Test
    void fillAboveThresholdAsksForOneMoreService() {
        ServiceTask t = task("autoscaling: {strategies: [players], scaleUpThreshold: 0.8}");
        // 2 × 17/20 = 85 % > 80 %.
        assertThat(desired(t, List.of(running("game-1", 17, 20), running("game-2", 17, 20)))).isEqualTo(3);
        // 2 × 16/20 = 80 % : pas au-dessus du seuil.
        assertThat(desired(t, List.of(running("game-1", 16, 20), running("game-2", 16, 20)))).isEqualTo(2);
    }

    @Test
    void startingAndPreparedServicesDoNotCountInTheFill() {
        ServiceTask t = task("autoscaling: {strategies: [players]}");
        var services = List.of(running("game-1", 18, 20), svc("game-2", Phase.Starting, 0, 20),
                prepared(running("game-3", 0, 20)));
        assertThat(PlayersScalingStrategy.fill(Autoscaler.routable(services))).isEqualTo(0.9);
        assertThat(desired(t, services)).isEqualTo(3);
    }

    @Test
    void servicesEmptyLongerThanIdleAreRemoved() {
        ServiceTask t = task("autoscaling: {strategies: [players], idleStopSeconds: 300}");
        emptySince(t, "game-3", NOW.minusSeconds(360));
        emptySince(t, "game-2", NOW.minusSeconds(120));
        var services = List.of(running("game-1", 5, 20), running("game-2", 0, 20), running("game-3", 0, 20));

        assertThat(desired(t, services)).isEqualTo(2);
        assertThat(PlayersScalingStrategy.idle(t, services, NOW)).extracting(s -> s.getMetadata().getName())
                .containsExactly("game-3");
    }

    @Test
    void noCapacityInformationMeansNoScaleUp() {
        ServiceTask t = task("autoscaling: {strategies: [players]}");
        assertThat(desired(t, List.of(svc("game-1", Phase.Starting, 0, 0)))).isEqualTo(1);
    }
}
