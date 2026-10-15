package io.ksmc.controller.messaging;

import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.health.HealthCheck;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connexion NATS du controller, avec reconnexion infinie ; sert aussi de vérification de readiness. */
public final class NatsConnector implements HealthCheck, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NatsConnector.class);

    private final Options options;
    private volatile Connection connection;
    private volatile boolean closed;

    public NatsConnector(ControllerConfig config) {
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
                connection = Nats.connect(options);
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
