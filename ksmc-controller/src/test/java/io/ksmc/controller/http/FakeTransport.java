package io.ksmc.controller.http;

import io.ksmc.wire.messaging.ChannelApi;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventApi;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingTransport;
import io.ksmc.wire.messaging.Subscription;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Messagerie en mémoire : seuls les événements sont gérés (types {@code a.b}, motifs {@code a.*} et {@code >}). */
final class FakeTransport implements MessagingTransport {

    private record Sub(EventFilter filter, EventApi.Handler handler) {
    }

    final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    static boolean typeMatches(String pattern, String type) {
        if (pattern.equals(">")) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return type.startsWith(prefix) && !type.substring(prefix.length()).contains(".");
        }
        if (pattern.endsWith(".>")) {
            return type.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(type);
    }

    void publish(CloudEvent e) {
        e.sequence = sequence.incrementAndGet();
        for (Sub s : subs) {
            if (typeMatches(s.filter().type, e.type) && s.filter().matches(e)) {
                s.handler().handle(e);
            }
        }
    }

    int subscriptions() {
        return subs.size();
    }

    @Override
    public Identity identity() {
        return Identity.controller("test");
    }

    @Override
    public ChannelApi channels() {
        throw new UnsupportedOperationException();
    }

    @Override
    public EventApi events() {
        return new EventApi() {
            public void publish(CloudEvent event) {
                FakeTransport.this.publish(event);
            }

            public Subscription subscribe(EventFilter filter, Handler handler) {
                Sub s = new Sub(filter, handler);
                subs.add(s);
                return () -> subs.remove(s);
            }
        };
    }

    @Override
    public KeyValueApi keyValue(String bucket) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void close() {
    }
}
