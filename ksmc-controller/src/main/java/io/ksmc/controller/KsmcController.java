package io.ksmc.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.health.KubernetesHealthCheck;
import io.ksmc.controller.http.HttpServer;
import io.ksmc.controller.messaging.NatsConnector;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Point d'entrée du controller Ksmc : opérateur Kubernetes + serveur HTTP + connexion NATS. */
public final class KsmcController {
    private static final Logger LOG = LoggerFactory.getLogger(KsmcController.class);

    public static void main(String[] args) {
        ControllerConfig config = ControllerConfig.fromEnv(System.getenv());
        LOG.info("Démarrage de Ksmc controller (namespace {})", config.namespace());

        KubernetesClient client = new KubernetesClientBuilder().build();
        HealthRegistry health = new HealthRegistry();
        health.register(new KubernetesHealthCheck(client, Clock.systemUTC()));

        NatsConnector nats = new NatsConnector(config);
        health.register(nats);
        nats.connectAsync();

        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        // Les reconcilers sont enregistrés par les changes suivants (p02+).
        if (!operator.getRegisteredControllers().isEmpty()) {
            operator.start();
        }

        HttpServer http = new HttpServer(health).start(config.httpPort());
        LOG.info("HTTP prêt sur le port {}", http.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Arrêt du controller");
            http.close();
            operator.stop();
            nats.close();
            client.close();
        }, "ksmc-shutdown"));
    }

    private KsmcController() {
    }
}
