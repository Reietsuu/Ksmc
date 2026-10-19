package io.ksmc.plugin;

import java.util.concurrent.CompletableFuture;

/** Bucket clé-valeur ; valeurs texte (JSON conseillé). Les futurs sont complétés sur le thread principal. */
public interface KeyValue {

    interface Watcher {
        /** {@code value == null} : clé supprimée. Appelé sur le thread principal. */
        void changed(String key, String value);
    }

    /** Valeur courante, ou {@code null}. */
    CompletableFuture<String> get(String key);

    CompletableFuture<Void> put(String key, String value);

    CompletableFuture<Void> delete(String key);

    /** Reçoit les valeurs actuelles puis les changements des clés correspondant au motif ({@code >} = toutes). */
    Subscription watch(String keyPattern, Watcher watcher);
}
