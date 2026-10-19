package io.ksmc.controller.task;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.spi.ScalingStrategy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stratégie {@code schedule} : minimum programmé. Une règle est active entre une occurrence de {@code from} et
 * l'occurrence suivante de {@code to} (cron Unix, évalué dans {@code timeZone}) ; la cible est le plus grand
 * {@code minServiceCount} des règles actives.
 */
public final class ScheduleScalingStrategy implements ScalingStrategy {
    public static final String NAME = "schedule";

    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private final Map<String, ExecutionTime> cache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int desiredServices(Context context) {
        ServiceTaskSpec.Autoscaling a = context.task().getSpec().autoscaling;
        if (a == null || a.schedule.isEmpty()) {
            return NO_OPINION;
        }
        int best = NO_OPINION;
        for (ServiceTaskSpec.ScheduleRule rule : a.schedule) {
            if (active(rule, context.now())) {
                best = Math.max(best, rule.minServiceCount);
            }
        }
        return best;
    }

    /** Vrai si la dernière occurrence de {@code from} (minute courante incluse) est postérieure à celle de {@code to}. */
    public boolean active(ServiceTaskSpec.ScheduleRule rule, Instant now) {
        ZonedDateTime at = now.atZone(ZoneId.of(rule.timeZone == null ? "UTC" : rule.timeZone))
                .truncatedTo(ChronoUnit.MINUTES);
        Optional<ZonedDateTime> from = last(rule.from, at);
        Optional<ZonedDateTime> to = last(rule.to, at);
        return from.isPresent() && (to.isEmpty() || from.get().isAfter(to.get()));
    }

    /** Dernière occurrence à ou avant {@code at}. */
    private Optional<ZonedDateTime> last(String expression, ZonedDateTime at) {
        ExecutionTime time = cache.computeIfAbsent(expression, e -> ExecutionTime.forCron(PARSER.parse(e)));
        return time.isMatch(at) ? Optional.of(at) : time.lastExecution(at);
    }

    /** Lève {@link IllegalArgumentException} si une expression ou un fuseau est invalide. */
    public static void validate(ServiceTaskSpec.ScheduleRule rule) {
        PARSER.parse(rule.from).validate();
        PARSER.parse(rule.to).validate();
        ZoneId.of(rule.timeZone == null ? "UTC" : rule.timeZone);
    }
}
