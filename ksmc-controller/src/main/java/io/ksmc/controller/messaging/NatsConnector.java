package io.ksmc.controller.messaging;

import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.health.HealthCheck;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.Identity;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connexion NATS du controller, avec reconnexion infinie ; sert aussi de vérification de readiness. */
public final class NatsConnector implements HealthCheck, CloudEvents, AutoCloseable {
    /** Buckets KV créés par le controller au démarrage. */
    static final String[] BUCKETS = {"shared"};
    private static final Logger LOG = LoggerFactory.getLogger(NatsConnector.class);

    private final Options options;
    private final String namespace;
    private volatile Connection connection;
    private volatile NatsMessaging messaging;
    private final java.util.List<java.util.function.Consumer<NatsMessaging>> onReady =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public NatsConnector(ControllerConfig config) {
        this.namespace = config.namespace();
        Options.Builder builder = new Options.Builder()
                .server(config.natsUrl())
                .connectionName("ksmc-controller")
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(2))
                .connectionListener((conn, event) -> LOG.info("NATS: {}", event));
        if (config.natsUser() != null) {
            builder.userInfo(config.natsUser(), config.natsPassword());
        }
        this.options = builder.build();
    }

    /** Connexion en arrière-plan : le controller démarre même si NATS n'est pas encore prêt. */
    public void connectAsync() {
        Thread.ofVirtual().name("nats-connect").start(this::connectLoop);
    }

    private void connectLoop() {
        while (connection == null && !closed) {
            try {
                Connection c = Nats.connect(options);
                NatsMessaging m = NatsMessaging.wrap(Identity.controller(namespace), c);
                m.ensureInfrastructure(BUCKETS);
                for (var listener : onReady) {
                    listener.accept(m);
                }
                messaging = m;
                connection = c;
                LOG.info("Messagerie NATS prête (stream d'événements et buckets KV vérifiés)");
            } catch (Exception e) {
                LOG.warn("NATS indisponible ({}), nouvel essai dans 2 s", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public Connection connection() {
        return connection;
    }

    /** Action exécutée une fois la messagerie prête (avant que le controller ne soit déclaré prêt). */
    public void onReady(java.util.function.Consumer<NatsMessaging> listener) {
        onReady.add(listener);
    }

    /** Messagerie du controller, ou {@code null} tant que NATS n'est pas joignable. */
    public NatsMessaging messaging() {
        return messaging;
    }

    /**
     * Envoie une commande console à un service ; renvoie l'accusé du wrapper ({@code ok} ou {@code error: ...}).
     *
     * @throws IllegalStateException si NATS est indisponible ou si le service ne répond pas
     */
    public String sendCommand(String service, String command) {
        Connection c = connection;
        if (c == null) {
            throw new IllegalStateException("messaging unavailable");
        }
        try {
            io.nats.client.Message reply = c.request(io.ksmc.messaging.nats.Subjects.command(namespace, service),
                    command.getBytes(java.nio.charset.StandardCharsets.UTF_8), Duration.ofSeconds(5));
            if (reply == null || reply.isStatusMessage()) {
                throw new IllegalStateException("service " + service + " did not acknowledge the command");
            }
            return new String(reply.getData(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Override
    public void publish(CloudEvent event) {
        NatsMessaging m = messaging;
        if (m == null) {
            LOG.debug("événement {} non publié : NATS indisponible", event.type);
            return;
        }
        try {
            m.events().publish(event);
        } catch (RuntimeException e) {
            LOG.warn("événement {} non publié : {}", event.type, e.getMessage());
        }
    }

    @Override
    public String name() {
        return "nats";
    }

    @Override
    public Result check() {
        Connection c = connection;
        if (c == null) {
            return Result.down("not connected yet");
        }
        return c.getStatus() == Connection.Status.CONNECTED ? Result.up() : Result.down(c.getStatus().name());
    }

    @Override
    public void close() {
        closed = true;
        if (connection != null) {
            try {
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
