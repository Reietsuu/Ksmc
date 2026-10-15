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
        int target = spec.minServiceCount;
        for (String name : names) {
            ScalingStrategy s = strategies.get(name);
            if (s != null) {
                target = Math.max(target, s.desiredServices(context));
            }
        }
        return bound(spec, target);
    }

    static int bound(ServiceTaskSpec spec, int value) {
        int v = Math.max(spec.minServiceCount, value);
        return spec.maxServiceCount >= 0 ? Math.min(spec.maxServiceCount, v) : v;
    }

    public boolean has(String name) {
        return strategies.containsKey(name);
    }
}
