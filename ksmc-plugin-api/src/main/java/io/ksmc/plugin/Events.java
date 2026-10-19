package io.ksmc.plugin;

/** Abonnement aux événements du cloud ; les écouteurs sont appelés sur le thread principal. */
public interface Events {

    interface Listener {
        void onEvent(CloudEventInfo event);
    }

    /**
     * @param typePattern type exact ({@code service.started}), préfixe joker ({@code service.*}) ou {@code >} (tous)
     */
    Subscription subscribe(String typePattern, Listener listener);
}
