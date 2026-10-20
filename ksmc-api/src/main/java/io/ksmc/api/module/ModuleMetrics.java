package io.ksmc.api.module;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Métriques d'un module, exposées sur {@code /metrics} du controller avec le préfixe {@code ksmc_module_<id>_}.
 * Noms en minuscules ({@code [a-z][a-z0-9_]*}) ; {@code tags} = paires clé/valeur. Les métriques sont retirées à la
 * désactivation du module.
 */
public interface ModuleMetrics {

    /** Compteur {@code ksmc_module_<id>_<name>_total}. */
    Counter counter(String name, String... tags);

    /** Jauge lue à chaque collecte. */
    void gauge(String name, Supplier<? extends Number> value, String... tags);

    /** Durées ({@code ksmc_module_<id>_<name>_seconds}). */
    Timer timer(String name, String... tags);

    interface Counter {
        default void increment() {
            increment(1);
        }

        void increment(double amount);
    }

    interface Timer {
        void record(Duration duration);
    }
}
