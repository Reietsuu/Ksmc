package io.ksmc.controller.task;

import static io.ksmc.controller.task.AutoscalingTestSupport.prepared;
import static io.ksmc.controller.task.AutoscalingTestSupport.running;
import static io.ksmc.controller.task.AutoscalingTestSupport.svc;
import static io.ksmc.controller.task.AutoscalingTestSupport.task;
import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoscalerTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    static ServiceTask scaledUp(String spec, Instant when) {
        ServiceTask t = task(spec);
        t.getStatus().autoscaling.lastDecision = Autoscaler.SCALED_UP;
        t.getStatus().autoscaling.lastScaleTime = when.toString();
        return t;
    }

    @Test
    void noInverseDecisionDuringCooldown() {
        ServiceTask t = scaledUp("autoscaling: {strategies: [players], cooldownSeconds: 60}", NOW.minusSeconds(10));
        // Pic bref : la cible redescend 10 s après la montée → maintien.
        Autoscaler.Decision d = Autoscaler.decide(t, 3, 0, 2, 1, NOW, "x");
        assertThat(d.target()).isEqualTo(3);
        assertThat(d.direction()).isNull();

        // Après le cooldown, la réduction est appliquée.
        Autoscaler.Decision later = Autoscaler.decide(t, 3, 0, 2, 1, NOW.plusSeconds(60), "x");
        assertThat(later.target()).isEqualTo(2);
        assertThat(later.direction()).isEqualTo(Autoscaler.SCALED_DOWN);
    }

    @Test
    void oneStartAtATimeUnlessAllowed() {
        ServiceTask t = task("autoscaling: {strategies: [players]}");
        assertThat(Autoscaler.decide(t, 2, 1, 4, 1, NOW, "x").target()).isEqualTo(2);
        assertThat(Autoscaler.decide(t, 2, 0, 4, 1, NOW, "x").target()).isEqualTo(3);

        ServiceTask burst = task("autoscaling: {strategies: [players], maxConcurrentStarts: 3}");
        assertThat(Autoscaler.decide(burst, 2, 1, 6, 1, NOW, "x").target()).isEqualTo(4);
    }

    @Test
    void baselineIsReachedImmediately() {
        ServiceTask t = task("autoscaling: {strategies: [schedule]}");
        // Minimum programmé à 5 : pas de limite de démarrages jusqu'au minimum.
        Autoscaler.Decision d = Autoscaler.decide(t, 2, 1, 5, 5, NOW, "x");
        assertThat(d.target()).isEqualTo(5);
        assertThat(d.direction()).isEqualTo(Autoscaler.SCALED_UP);
    }

    @Test
    void emptinessIsRememberedUntilAPlayerJoins() {
        ServiceTaskStatus.AutoscalingState state = new ServiceTaskStatus.AutoscalingState();
        Autoscaler.observe(state, List.of(running("game-1", 0, 20), svc("game-2", Phase.Starting, 0, 20),
                prepared(running("game-3", 0, 20))), NOW);
        assertThat(state.emptySince).containsOnlyKeys("game-1").containsEntry("game-1", NOW.toString());

        Autoscaler.observe(state, List.of(running("game-1", 0, 20)), NOW.plusSeconds(30));
        assertThat(state.emptySince).containsEntry("game-1", NOW.toString());

        Autoscaler.observe(state, List.of(running("game-1", 1, 20)), NOW.plusSeconds(60));
        assertThat(state.emptySince).isEmpty();
    }

    @Test
    void autoscalingIsEnabledByNonStaticStrategiesOrPreparedServices() {
        assertThat(Autoscaler.enabled(task("minServiceCount: 1"))).isFalse();
        assertThat(Autoscaler.enabled(task("autoscaling: {strategies: [static]}"))).isFalse();
        assertThat(Autoscaler.enabled(task("autoscaling: {strategies: [players]}"))).isTrue();
        assertThat(Autoscaler.enabled(task("autoscaling: {preparedServices: 1}"))).isTrue();
    }
}
