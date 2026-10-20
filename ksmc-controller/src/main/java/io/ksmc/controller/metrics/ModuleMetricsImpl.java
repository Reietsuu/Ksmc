package io.ksmc.controller.metrics;

import io.ksmc.api.module.ModuleMetrics;
import io.ksmc.api.module.Registry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Métriques d'un module : préfixe {@code ksmc.module.<id>.} imposé, retirées à la désactivation. */
public final class ModuleMetricsImpl implements ModuleMetrics {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final MeterRegistry registry;
    private final String prefix;
    private final List<Meter> meters = new CopyOnWriteArrayList<>();

    public ModuleMetricsImpl(MeterRegistry registry, String moduleId, Registry moduleRegistry) {
        this.registry = registry;
        this.prefix = "ksmc.module." + moduleId.toLowerCase().replaceAll("[^a-z0-9]", "_") + ".";
        moduleRegistry.onDisable(() -> meters.forEach(registry::remove));
    }

    private String name(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("nom de métrique invalide (attendu [a-z][a-z0-9_]*) : " + name);
        }
        return prefix + name;
    }

    private static String[] tags(String[] tags) {
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("tags : paires clé/valeur attendues");
        }
        return tags;
    }

    private <M extends Meter> M track(M meter) {
        if (!meters.contains(meter)) {
            meters.add(meter);
        }
        return meter;
    }

    @Override
    public ModuleMetrics.Counter counter(String name, String... tags) {
        io.micrometer.core.instrument.Counter c = track(io.micrometer.core.instrument.Counter.builder(name(name)).tags(tags(tags)).register(registry));
        return c::increment;
    }

    @Override
    public void gauge(String name, Supplier<? extends Number> value, String... tags) {
        track(Gauge.builder(name(name), () -> {
            Number n = value.get();
            return n == null ? Double.NaN : n.doubleValue();
        }).tags(tags(tags)).register(registry));
    }

    @Override
    public ModuleMetrics.Timer timer(String name, String... tags) {
        io.micrometer.core.instrument.Timer t = track(io.micrometer.core.instrument.Timer.builder(name(name)).tags(tags(tags)).register(registry));
        return (Duration d) -> t.record(d);
    }
}
