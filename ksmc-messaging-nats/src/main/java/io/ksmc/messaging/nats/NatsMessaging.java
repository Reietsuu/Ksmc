package io.ksmc.messaging.nats;

import io.ksmc.wire.Wire;
import io.ksmc.wire.messaging.ChannelApi;
import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventApi;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingException;
import io.ksmc.wire.messaging.MessagingTransport;
import io.ksmc.wire.messaging.Subscription;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.JetStreamApiException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link MessagingTransport} NATS (+ JetStream pour les événements et le KV). Compatible Java 8. */
public final class NatsMessaging implements MessagingTransport {
    private static final Logger LOG = Logger.getLogger(NatsMessaging.class.getName());
    static final Charset UTF8 = Charset.forName("UTF-8");
    /** Durée de conservation des événements (rejouables au moins pendant ce temps). */
    public static final Duration EVENT_RETENTION = Duration.ofHours(1);

    private final Identity identity;
    private final Connection connection;
    private final Channels channels = new Channels();
    private final Events events = new Events();

    private NatsMessaging(Identity identity, Connection connection) {
        this.identity = identity;
        this.connection = connection;
    }

    /** Connexion (bloquante jusqu'à la première connexion, puis reconnexion automatique illimitée). */
    public static NatsMessaging connect(Identity identity, String url, String user, String password)
            throws IOException, InterruptedException {
        Options.Builder b = new Options.Builder().server(url).connectionName(identity.sender.toString())
                .maxReconnects(-1).reconnectWait(Duration.ofSeconds(2));
        if (user != null && !user.isEmpty()) {
            b.userInfo(user, password);
        }
        return new NatsMessaging(identity, Nats.connectReconnectOnConnect(b.build()));
    }

    /** Utilise une connexion existante (ex. celle du controller). */
    public static NatsMessaging wrap(Identity identity, Connection connection) {
        return new NatsMessaging(identity, connection);
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public Identity identity() {
        return identity;
    }

    @Override
    public ChannelApi channels() {
        return channels;
    }

    @Override
    public EventApi events() {
        return events;
    }

    @Override
    public KeyValueApi keyValue(String bucket) {
        try {
            return new NatsKeyValue(connection.keyValue(Subjects.bucket(identity.namespace, bucket)));
        } catch (IOException e) {
            throw new MessagingException("Unavailable", e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connection.getStatus() == Connection.Status.CONNECTED;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Crée (si besoin) le stream d'événements et un bucket KV ; réservé au controller. */
    public void ensureInfrastructure(String... buckets) throws IOException {
        JetStreamManagement jsm = connection.jetStreamManagement();
        String stream = Subjects.eventStream(identity.namespace);
        StreamConfiguration config = StreamConfiguration.builder().name(stream)
                .subjects("ksmc." + identity.namespace + ".ev.>")
                .storageType(StorageType.File).maxAge(EVENT_RETENTION).build();
        try {
            try {
                StreamInfo info = jsm.getStreamInfo(stream);
                if (!info.getConfiguration().getMaxAge().equals(EVENT_RETENTION)) {
                    jsm.updateStream(config);
                }
            } catch (JetStreamApiException notFound) {
                jsm.addStream(config);
            }
            for (String bucket : buckets) {
                String name = Subjects.bucket(identity.namespace, bucket);
                try {
                    connection.keyValueManagement().getStatus(name);
                } catch (JetStreamApiException notFound) {
                    connection.keyValueManagement().create(KeyValueConfiguration.builder().name(name)
                            .storageType(StorageType.File).maxHistoryPerKey(1).build());
                }
            }
        } catch (JetStreamApiException e) {
            throw new IOException("JetStream: " + e.getMessage(), e);
        }
    }

    /** Crée un bucket KV avec TTL (les entrées expirent). Réservé au controller. */
    public void ensureBucket(String bucket, Duration ttl) throws IOException {
        String name = Subjects.bucket(identity.namespace, bucket);
        try {
            try {
                connection.keyValueManagement().getStatus(name);
            } catch (JetStreamApiException notFound) {
                // Stockage fichier : le stockage mémoire de JetStream peut être désactivé côté serveur.
                connection.keyValueManagement().create(KeyValueConfiguration.builder().name(name)
                        .storageType(StorageType.File).ttl(ttl).maxHistoryPerKey(1).build());
            }
        } catch (JetStreamApiException e) {
            throw new IOException("JetStream: " + e.getMessage(), e);
        }
    }

    // ---- Channels --------------------------------------------------------------------------------

    private final class Channels implements ChannelApi {

        @Override
        public void send(ChannelMessage message) {
            message.sender = identity.sender;
            connection.publish(Subjects.channel(identity.namespace, message.channel, message.target),
                    Wire.toJson(message).getBytes(UTF8));
        }

        @Override
        public CompletableFuture<ChannelMessage> request(ChannelMessage message, long timeoutMillis) {
            message.sender = identity.sender;
            final CompletableFuture<ChannelMessage> result = new CompletableFuture<ChannelMessage>();
            CompletableFuture<Message> raw = connection.requestWithTimeout(
                    Subjects.channel(identity.namespace, message.channel, message.target),
                    Wire.toJson(message).getBytes(UTF8), Duration.ofMillis(timeoutMillis));
            raw.whenComplete((reply, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    String text = String.valueOf(cause.getMessage());
                    String code = text.contains("503") || text.toLowerCase().contains("no responders")
                            ? "NoResponders" : "Timeout";
                    result.completeExceptionally(new MessagingException(code, message.channel + "/" + message.message, cause));
                } else if (reply.isStatusMessage()) {
                    result.completeExceptionally(new MessagingException(
                            reply.getStatus().getCode() == 503 ? "NoResponders" : "Error",
                            message.channel + "/" + message.message));
                } else {
                    result.complete(Wire.fromJson(new String(reply.getData(), UTF8), ChannelMessage.class));
                }
            });
            return result;
        }

        @Override
        public Subscription subscribe(String channel, Handler handler) {
            return subscribeSubjects(Subjects.channelInbox(identity, channel), handler);
        }

        @Override
        public Subscription subscribeAll(String channel, Handler handler) {
            if (!identity.isController()) {
                throw new MessagingException("Forbidden", "subscribeAll is reserved to the controller and its modules");
            }
            return subscribeSubjects(java.util.Collections.singletonList(Subjects.channelAll(identity.namespace, channel)),
                    handler);
        }

        private Subscription subscribeSubjects(List<String> subjects, final Handler handler) {
            final Dispatcher dispatcher = connection.createDispatcher(msg -> {
                try {
                    ChannelMessage in = Wire.fromJson(new String(msg.getData(), UTF8), ChannelMessage.class);
                    ChannelMessage reply = handler.handle(in);
                    if (reply != null && msg.getReplyTo() != null) {
                        reply.sender = identity.sender;
                        connection.publish(msg.getReplyTo(), Wire.toJson(reply).getBytes(UTF8));
                    }
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "channel handler failed on " + msg.getSubject(), e);
                }
            });
            for (String s : subjects) {
                dispatcher.subscribe(s);
            }
            return () -> connection.closeDispatcher(dispatcher);
        }
    }

    // ---- Événements ------------------------------------------------------------------------------

    private final class Events implements EventApi {

        @Override
        public void publish(CloudEvent event) {
            event.namespace = identity.namespace;
            try {
                connection.jetStream().publish(Subjects.event(identity.namespace, event.type),
                        Wire.toJson(event).getBytes(UTF8));
            } catch (IOException | JetStreamApiException e) {
                throw new MessagingException("PublishFailed", event.type + ": " + e.getMessage(), e);
            }
        }

        @Override
        public Subscription subscribe(final EventFilter filter, final Handler handler) {
            try {
                ConsumerConfiguration.Builder cc = ConsumerConfiguration.builder();
                if (filter.sinceSequence > 0) {
                    cc.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(filter.sinceSequence);
                } else if (filter.sinceTime > 0) {
                    cc.deliverPolicy(DeliverPolicy.ByStartTime)
                            .startTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(filter.sinceTime), ZoneOffset.UTC));
                } else {
                    cc.deliverPolicy(DeliverPolicy.New);
                }
                PushSubscribeOptions options = PushSubscribeOptions.builder().ordered(true)
                        .stream(Subjects.eventStream(identity.namespace)).configuration(cc.build()).build();
                JetStream js = connection.jetStream();
                final Dispatcher dispatcher = connection.createDispatcher();
                final JetStreamSubscription sub = js.subscribe(Subjects.event(identity.namespace, filter.type),
                        dispatcher, msg -> {
                            try {
                                CloudEvent e = Wire.fromJson(new String(msg.getData(), UTF8), CloudEvent.class);
                                e.sequence = msg.metaData().streamSequence();
                                if (filter.matches(e)) {
                                    handler.handle(e);
                                }
                            } catch (RuntimeException ex) {
                                LOG.log(Level.WARNING, "event handler failed on " + msg.getSubject(), ex);
                            }
                        }, true, options);
                return () -> {
                    try {
                        sub.unsubscribe();
                    } catch (IllegalStateException ignored) {
                        // déjà fermé
                    }
                    connection.closeDispatcher(dispatcher);
                };
            } catch (IOException | JetStreamApiException e) {
                throw new MessagingException("SubscribeFailed", filter.type + ": " + e.getMessage(), e);
            }
        }
    }
}
