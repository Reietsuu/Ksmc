package io.ksmc.wire.messaging;

/** Abonnement actif ; {@link #close()} l'arrête. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
