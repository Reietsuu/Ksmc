package io.ksmc.bridge;

import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingException;
import io.ksmc.wire.messaging.Subscription;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bucket KV en mémoire avec révisions (comparer-et-écrire) pour les tests unitaires. */
final class MemoryKeyValue implements KeyValueApi {
    final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    @Override
    public Entry get(String key) {
        return entries.get(key);
    }

    @Override
    public synchronized long put(String key, byte[] value) {
        long r = revision.incrementAndGet();
        entries.put(key, new Entry(key, value, r));
        return r;
    }

    @Override
    public synchronized long update(String key, byte[] value, long expectedRevision) {
        Entry e = entries.get(key);
        long current = e == null ? 0 : e.revision;
        if (current != expectedRevision) {
            throw new MessagingException("Conflict", key);
        }
        return put(key, value);
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }

    @Override
    public Subscription watch(String keyPattern, Watcher watcher) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Subscription watchWithCurrent(String keyPattern, Watcher watcher) {
        throw new UnsupportedOperationException();
    }
}
