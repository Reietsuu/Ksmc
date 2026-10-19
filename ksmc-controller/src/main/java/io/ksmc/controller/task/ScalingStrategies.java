package io.ksmc.controller.task;

import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.spi.ScalingStrategy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registre des stratégies de scaling (intégrées + modules) et calcul de la cible bornée. */
public final class ScalingStrategies {
    private final Map<String, ScalingStrategy> strategies = new ConcurrentHashMap<>();

    public ScalingStrategies() {
        register(new StaticScalingStrategy());
        register(new PlayersScalingStrategy());
        register(new ScheduleScalingStrategy());
    }

    public void register(ScalingStrategy strategy) {
        strategies.put(strategy.name(), strategy);
    }

    public void unregister(ScalingStrategy strategy) {
        strategies.remove(strategy.name(), strategy);
    }

    /** Cible = max des propositions des stratégies de la task, bornée par min/max. Une stratégie inconnue est ignorée. */
    public int target(ScalingStrategy.Context context) {
        ServiceTaskSpec spec = context.task().getSpec();
        List<String> names = spec.autoscaling == null || spec.autoscaling.strategies.isEmpty()
                ? List.of(StaticScalingStrategy.NAME) : spec.autoscaling.strategies;
        // Le minimum et les démarrages manuels restent acquis quelles que soient les stratégies.
        int target = spec.minServiceCount + spec.extraServices;
        for (String name : names) {
            ScalingStrategy s = strategies.get(name);
            if (s != null) {
                target = Math.max(target, s.desiredServices(context));
            }
        }
        return bound(spec, target);
    }

    public static int bound(ServiceTaskSpec spec, int value) {
        int v = Math.max(spec.minServiceCount, value);
        return spec.maxServiceCount >= 0 ? Math.min(spec.maxServiceCount, v) : v;
    }

    /** Proposition d'une stratégie de la task (si elle est listée et connue), sinon {@link ScalingStrategy#NO_OPINION}. */
    public int proposal(String name, ScalingStrategy.Context context) {
        ServiceTaskSpec.Autoscaling a = context.task().getSpec().autoscaling;
        ScalingStrategy s = strategies.get(name);
        return a == null || s == null || !a.strategies.contains(name) ? ScalingStrategy.NO_OPINION : s.desiredServices(context);
    }

    public boolean has(String name) {
        return strategies.containsKey(name);
    }
}
