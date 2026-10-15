package io.ksmc.wire.messaging;

/** Événements du cloud : publication (controller et modules) et abonnement filtré avec reprise. */
public interface EventApi {

    interface Handler {
        void handle(CloudEvent event);
    }

    /** Publie un événement ; le namespace est renseigné automatiquement. Interdit aux services sauf {@code player.*}. */
    void publish(CloudEvent event);

    Subscription subscribe(EventFilter filter, Handler handler);
}
