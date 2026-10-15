package io.ksmc.wire.messaging;

/** Bucket clé-valeur partagé (révisions, comparer-et-écrire, surveillance). */
public interface KeyValueApi {

    final class Entry {
        public final String key;
        public final byte[] value;
        public final long revision;

        public Entry(String key, byte[] value, long revision) {
            this.key = key;
            this.value = value;
            this.revision = revision;
        }

        public String valueAsString() {
            return value == null ? null : new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    interface Watcher {
        /** {@code entry.value == null} : clé supprimée. */
        void changed(Entry entry);
    }

    /** Valeur courante, ou {@code null}. */
    Entry get(String key);

    /** Écrit et renvoie la nouvelle révision. */
    long put(String key, byte[] value);

    /** Écrit seulement si la révision courante vaut {@code expectedRevision} (0 = la clé ne doit pas exister). */
    long update(String key, byte[] value, long expectedRevision);

    void delete(String key);

    /** Surveille les changements des clés correspondant au motif ({@code >} = toutes). */
    Subscription watch(String keyPattern, Watcher watcher);

    /** Comme {@link #watch} mais reçoit d'abord les valeurs actuelles, puis les changements. */
    Subscription watchWithCurrent(String keyPattern, Watcher watcher);
}
