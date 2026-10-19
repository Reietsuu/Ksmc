package io.ksmc.controller.module;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.module.CliCommand;
import io.ksmc.api.module.DashboardPage;
import io.ksmc.api.module.ModuleContext;
import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.Registry;
import io.ksmc.api.module.RestRoutes;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contexte réel d'un module : les abonnements de messagerie, routes, commandes et pages passent par le
 * {@link Registry} du module et disparaissent à sa désactivation.
 */
public final class DefaultModuleContext implements ModuleContext {

    private final ModuleDescriptor descriptor;
    private final Registry registry;
    private final Supplier<JsonNode> config;
    private final ClassLoader classLoader;
    private final KubernetesClient kubernetes;
    private final String namespace;
    private final Supplier<? extends MessagingTransport> messaging;
    private final Path dataRoot;
    private final Logger logger;

    public DefaultModuleContext(ModuleDescriptor descriptor, Registry registry, Supplier<JsonNode> config,
            ClassLoader classLoader, KubernetesClient kubernetes, String namespace,
            Supplier<? extends MessagingTransport> messaging, Path dataRoot) {
        this.descriptor = descriptor;
        this.registry = registry;
        this.config = config;
        this.classLoader = classLoader;
        this.kubernetes = kubernetes;
        this.namespace = namespace;
        this.messaging = messaging;
        this.dataRoot = dataRoot;
        this.logger = LoggerFactory.getLogger("ksmc.module." + descriptor.id);
    }

    @Override
    public ModuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Registry registry() {
        return registry;
    }

    @Override
    public MessagingTransport messaging() {
        MessagingTransport m = messaging.get();
        if (m == null) {
            throw new MessagingException("Unavailable", "messagerie indisponible");
        }
        return new Tracked(m);
    }

    @Override
    public EventApi events() {
        return messaging().events();
    }

    @Override
    public KeyValueApi kv(String bucket) {
        return messaging().keyValue(bucket);
    }

    @Override
    public KubernetesClient kubernetes() {
        return kubernetes;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public RestRoutes rest() {
        return (method, path, permission, handler) -> {
            String clean = path.startsWith("/") ? path.substring(1) : path;
            registry.register(ModuleExtensions.Route.class,
                    new ModuleExtensions.Route(descriptor.id, method, clean, permission, handler, classLoader));
        };
    }

    @Override
    public void cliCommand(CliCommand command) {
        registry.register(ModuleExtensions.Cli.class, new ModuleExtensions.Cli(descriptor.id, command));
    }

    @Override
    public void dashboardPage(DashboardPage page) {
        registry.register(ModuleExtensions.Page.class, new ModuleExtensions.Page(descriptor.id, page, classLoader));
    }

    @Override
    public JsonNode config() {
        return config.get();
    }

    @Override
    public Path dataDirectory() {
        Path dir = dataRoot.resolve(descriptor.id);
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Logger logger() {
        return logger;
    }

    private Subscription track(Subscription s) {
        registry.onDisable(s);
        return s;
    }

    /** Messagerie du controller vue par un module : abonnements suivis, connexion non fermable. */
    private final class Tracked implements MessagingTransport {
        private final MessagingTransport m;

        Tracked(MessagingTransport m) {
            this.m = m;
        }

        @Override
        public Identity identity() {
            return m.identity();
        }

        @Override
        public ChannelApi channels() {
            ChannelApi c = m.channels();
            return new ChannelApi() {
                @Override
                public void send(ChannelMessage message) {
                    c.send(message);
                }

                @Override
                public CompletableFuture<ChannelMessage> request(ChannelMessage message, long timeoutMillis) {
                    return c.request(message, timeoutMillis);
                }

                @Override
                public Subscription subscribe(String channel, Handler handler) {
                    return track(c.subscribe(channel, handler));
                }

                @Override
                public Subscription subscribeAll(String channel, Handler handler) {
                    return track(c.subscribeAll(channel, handler));
                }
            };
        }

        @Override
        public EventApi events() {
            EventApi e = m.events();
            return new EventApi() {
                @Override
                public void publish(CloudEvent event) {
                    e.publish(event);
                }

                @Override
                public Subscription subscribe(EventFilter filter, Handler handler) {
                    return track(e.subscribe(filter, handler));
                }
            };
        }

        @Override
        public KeyValueApi keyValue(String bucket) {
            KeyValueApi kv = m.keyValue(bucket);
            return new KeyValueApi() {
                @Override
                public Entry get(String key) {
                    return kv.get(key);
                }

                @Override
                public long put(String key, byte[] value) {
                    return kv.put(key, value);
                }

                @Override
                public long update(String key, byte[] value, long expectedRevision) {
                    return kv.update(key, value, expectedRevision);
                }

                @Override
                public void delete(String key) {
                    kv.delete(key);
                }

                @Override
                public Subscription watch(String keyPattern, Watcher watcher) {
                    return track(kv.watch(keyPattern, watcher));
                }

                @Override
                public Subscription watchWithCurrent(String keyPattern, Watcher watcher) {
                    return track(kv.watchWithCurrent(keyPattern, watcher));
                }
            };
        }

        @Override
        public boolean isConnected() {
            return m.isConnected();
        }

        @Override
        public void close() {
            // La connexion appartient au controller.
        }
    }
}
