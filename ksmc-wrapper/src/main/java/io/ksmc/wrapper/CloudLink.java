package io.ksmc.wrapper;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.messaging.nats.Subjects;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.messaging.Identity;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import java.io.IOException;

/**
 * Lien du wrapper avec la messagerie du cloud : reçoit les commandes console envoyées par le controller.
 * Non bloquant : le serveur démarre même si NATS est indisponible ; la connexion se fait en arrière-plan.
 */
final class CloudLink implements AutoCloseable {
    /** Exécute une commande console sur le serveur. */
    interface CommandSink {
        void send(String command) throws IOException;
    }

    /** Reçoit les rapports du bridge de ce service (métriques). */
    interface ReportSink {
        void report(ServiceReport report);
    }

    private final WrapperEnv env;
    private final CommandSink sink;
    private final ReportSink reports;
    private volatile NatsMessaging messaging;
    private volatile boolean closed;

    CloudLink(WrapperEnv env, CommandSink sink) {
        this(env, sink, null);
    }

    CloudLink(WrapperEnv env, CommandSink sink, ReportSink reports) {
        this.env = env;
        this.sink = sink;
        this.reports = reports;
    }

    void startAsync() {
        if (env.natsUrl == null || env.natsUrl.isEmpty()) {
            Log.info("messagerie désactivée (KSMC_NATS_URL absent)");
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                connect();
            }
        }, "ksmc-cloud-link");
        t.setDaemon(true);
        t.start();
    }

    void connect() {
        try {
            Identity id = Identity.service(env.namespace, env.service, env.task, env.environment);
            NatsMessaging m = NatsMessaging.connect(id, env.natsUrl, env.natsUser, env.natsPassword);
            if (closed) {
                m.close();
                return;
            }
            final Connection c = m.connection();
            Dispatcher d = c.createDispatcher(msg -> {
                String command = new String(msg.getData(), IoUtil.UTF8).trim();
                String reply;
                try {
                    sink.send(command);
                    reply = "ok";
                } catch (IOException e) {
                    reply = "error: " + e.getMessage();
                }
                if (msg.getReplyTo() != null) {
                    c.publish(msg.getReplyTo(), reply.getBytes(IoUtil.UTF8));
                }
            });
            d.subscribe(Subjects.command(env.namespace, env.service));
            if (reports != null) {
                Dispatcher r = c.createDispatcher(msg -> {
                    try {
                        reports.report(Wire.fromJson(new String(msg.getData(), IoUtil.UTF8), ServiceReport.class));
                    } catch (RuntimeException e) {
                        Log.warn("rapport du bridge illisible : " + e.getMessage());
                    }
                });
                r.subscribe(BridgeSubjects.report(env.namespace, env.service));
            }
            c.flush(java.time.Duration.ofSeconds(5));
            messaging = m;
            Log.info("connecté à la messagerie du cloud");
        } catch (Exception e) {
            Log.warn("messagerie indisponible : " + e.getMessage());
        }
    }

    boolean isConnected() {
        return messaging != null && messaging.isConnected();
    }

    @Override
    public void close() {
        closed = true;
        if (messaging != null) {
            messaging.close();
        }
    }
}
