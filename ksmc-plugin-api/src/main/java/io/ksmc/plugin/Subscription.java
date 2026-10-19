package io.ksmc.plugin;

/** Abonnement à fermer quand il n'est plus utile (fermé automatiquement à l'arrêt du bridge). */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
