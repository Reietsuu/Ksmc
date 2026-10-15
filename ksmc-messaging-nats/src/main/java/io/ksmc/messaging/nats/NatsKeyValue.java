package io.ksmc.messaging.nats;

import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingException;
import io.ksmc.wire.messaging.Subscription;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.KeyValueWatchOption;
import io.nats.client.api.KeyValueWatcher;
import io.nats.client.impl.NatsKeyValueWatchSubscription;
import java.io.IOException;

/** {@link KeyValueApi} sur un bucket NATS KV. */
final class NatsKeyValue implements KeyValueApi {
    private final KeyValue kv;

    NatsKeyValue(KeyValue kv) {
        this.kv = kv;
    }

    @Override
    public Entry get(String key) {
        try {
            KeyValueEntry e = kv.get(key);
            return e == null || e.getOperation() != KeyValueOperation.PUT ? null
                    : new Entry(e.getKey(), e.getValue(), e.getRevision());
        } catch (IOException | JetStreamApiException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }

    @Override
    public long put(String key, byte[] value) {
        try {
            return kv.put(key, value);
        } catch (IOException | JetStreamApiException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }

    @Override
    public long update(String key, byte[] value, long expectedRevision) {
        try {
            return expectedRevision == 0 ? kv.create(key, value) : kv.update(key, value, expectedRevision);
        } catch (JetStreamApiException e) {
            // 10071 : wrong last sequence (révision différente)
            throw new MessagingException("Conflict", key + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            kv.delete(key);
        } catch (IOException | JetStreamApiException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }

    @Override
    public Subscription watch(String keyPattern, final Watcher watcher) {
        try {
            final NatsKeyValueWatchSubscription sub = kv.watch(keyPattern, new KeyValueWatcher() {
                @Override
                public void watch(KeyValueEntry e) {
                    boolean deleted = e.getOperation() != KeyValueOperation.PUT;
                    watcher.changed(new Entry(e.getKey(), deleted ? null : e.getValue(), e.getRevision()));
                }

                @Override
                public void endOfData() {
                    // fin de l'état initial : rien de particulier
                }
            }, KeyValueWatchOption.UPDATES_ONLY);
            return () -> {
                try {
                    sub.unsubscribe();
                } catch (Exception ignored) {
                    // déjà fermé
                }
            };
        } catch (IOException | JetStreamApiException | InterruptedException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }
}
