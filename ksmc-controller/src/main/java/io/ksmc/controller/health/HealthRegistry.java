package io.ksmc.controller.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Agrège les vérifications de readiness ; les modules pourront y ajouter les leurs. */
public final class HealthRegistry {
    private final List<HealthCheck> checks = new CopyOnWriteArrayList<>();

    public void register(HealthCheck check) {
        checks.add(check);
    }

    public void unregister(HealthCheck check) {
        checks.remove(check);
    }

    public Report readiness() {
        Map<String, HealthCheck.Result> results = new LinkedHashMap<>();
        boolean ready = true;
        for (HealthCheck check : checks) {
            HealthCheck.Result result;
            try {
                result = check.check();
            } catch (RuntimeException e) {
                result = HealthCheck.Result.down(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            results.put(check.name(), result);
            ready &= result.healthy();
        }
        return new Report(ready, results);
    }

    public record Report(boolean ready, Map<String, HealthCheck.Result> checks) {
    }
}
