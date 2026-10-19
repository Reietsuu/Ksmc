package io.ksmc.controller.task;

import static io.ksmc.controller.task.AutoscalingTestSupport.task;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.spi.ScalingStrategy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleScalingStrategyTest {

    final ScheduleScalingStrategy schedule = new ScheduleScalingStrategy();
    final ScalingStrategies all = new ScalingStrategies();

    static Instant paris(String localDateTime) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(localDateTime), ZoneId.of("Europe/Paris")).toInstant();
    }

    static ServiceTask evening() {
        return task("""
                minServiceCount: 2
                autoscaling:
                  strategies: [schedule, players]
                  schedule:
                    - {from: "0 18 * * *", to: "0 23 * * *", timeZone: Europe/Paris, minServiceCount: 5}
                """);
    }

    int desired(ServiceTask t, Instant at) {
        return schedule.desiredServices(new ScalingStrategy.Context(t, List.of(), at));
    }

    @Test
    void eveningRuleRaisesTheMinimumBetween18And23Paris() {
        ServiceTask t = evening();
        assertThat(desired(t, paris("2026-10-19T17:59"))).isEqualTo(ScalingStrategy.NO_OPINION);
        assertThat(desired(t, paris("2026-10-19T18:00"))).isEqualTo(5);
        assertThat(desired(t, paris("2026-10-19T22:59:30"))).isEqualTo(5);
        assertThat(desired(t, paris("2026-10-19T23:00"))).isEqualTo(ScalingStrategy.NO_OPINION);

        // Composition : la cible combinée retombe au minimum de base après 23 h.
        assertThat(all.target(new ScalingStrategy.Context(t, List.of(), paris("2026-10-19T20:00")))).isEqualTo(5);
        assertThat(all.target(new ScalingStrategy.Context(t, List.of(), paris("2026-10-19T23:30")))).isEqualTo(2);
    }

    @Test
    void overnightRulesAndHighestActiveMinimumWin() {
        ServiceTask t = task("""
                autoscaling:
                  strategies: [schedule]
                  schedule:
                    - {from: "0 22 * * *", to: "0 6 * * *", timeZone: UTC, minServiceCount: 1}
                    - {from: "0 20 * * 5", to: "0 2 * * 6", timeZone: UTC, minServiceCount: 4}
                """);
        assertThat(desired(t, Instant.parse("2026-10-20T03:00:00Z"))).isEqualTo(1);
        assertThat(desired(t, Instant.parse("2026-10-20T07:00:00Z"))).isEqualTo(ScalingStrategy.NO_OPINION);
        // Vendredi 23 octobre 2026, 23 h UTC : les deux règles, la plus haute gagne.
        assertThat(desired(t, Instant.parse("2026-10-23T23:00:00Z"))).isEqualTo(4);
    }

    @Test
    void invalidExpressionsAreDetected() {
        ServiceTaskSpec.ScheduleRule rule = new ServiceTaskSpec.ScheduleRule();
        rule.from = "61 * * * *";
        rule.to = "0 1 * * *";
        assertThatThrownBy(() -> ScheduleScalingStrategy.validate(rule)).isInstanceOf(IllegalArgumentException.class);
    }
}
