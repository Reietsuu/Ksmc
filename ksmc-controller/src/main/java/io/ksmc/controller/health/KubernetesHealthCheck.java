package io.ksmc.controller.health;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Vérifie que l'API Kubernetes répond ; le résultat est mis en cache pour ne pas la solliciter à chaque sonde. */
public final class KubernetesHealthCheck implements HealthCheck {
    private static final Duration TTL = Duration.ofSeconds(10);

    private final KubernetesClient client;
    private final Clock clock;
    private volatile Result last;
    private volatile Instant lastAt = Instant.EPOCH;

    public KubernetesHealthCheck(KubernetesClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public Result check() {
        Instant now = clock.instant();
        if (last == null || now.isAfter(lastAt.plus(TTL))) {
            try {
                client.getKubernetesVersion();
                last = Result.up();
            } catch (RuntimeException e) {
                last = Result.down(e.getMessage());
            }
            lastAt = now;
        }
        return last;
    }
}
