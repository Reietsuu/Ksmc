package io.ksmc.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.health.KubernetesHealthCheck;
import io.ksmc.controller.http.HttpServer;
import io.ksmc.controller.internal.InternalApi;
import io.ksmc.controller.internal.PodAuthenticator;
import io.ksmc.controller.messaging.NatsConnector;
import io.ksmc.controller.reconciler.SoftwareDefinitionReconciler;
import io.ksmc.controller.software.JdkResolverContext;
import io.ksmc.controller.software.SoftwareCatalog;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Point d'entrée du controller Ksmc : opérateur Kubernetes + serveur HTTP + connexion NATS. */
public final class KsmcController {
    private static final Logger LOG = LoggerFactory.getLogger(KsmcController.class);

    public static void main(String[] args) throws Exception {
        ControllerConfig config = ControllerConfig.fromEnv(System.getenv());
        LOG.info("Démarrage de Ksmc controller (namespace {})", config.namespace());
        Clock clock = Clock.systemUTC();

        KubernetesClient client = new KubernetesClientBuilder().build();
        HealthRegistry health = new HealthRegistry();
        health.register(new KubernetesHealthCheck(client, clock));

        NatsConnector nats = new NatsConnector(config);
        health.register(nats);
        nats.connectAsync();

        FileArtifactStorage storage = new FileArtifactStorage(Path.of(System.getenv().getOrDefault("KSMC_DATA_DIR", "/data")));
        SoftwareCatalog catalog = new SoftwareCatalog(new JdkResolverContext(), clock);

        Operator operator = new Operator(o -> o.withKubernetesClient(client).withUseSSAToPatchPrimaryResource(false));
        register(operator, config, new SoftwareDefinitionReconciler(catalog));
        operator.start();

        InternalApi internal = new InternalApi(new PodAuthenticator(client, clock), client, storage,
                (ns, name) -> Optional.empty());
        HttpServer http = new HttpServer(health, List.of(internal)).start(config.httpPort());
        LOG.info("HTTP prêt sur le port {}", http.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Arrêt du controller");
            http.close();
            operator.stop();
            nats.close();
            client.close();
        }, "ksmc-shutdown"));
    }

    /** Enregistre un reconciler limité au namespace d'installation. */
    private static void register(Operator operator, ControllerConfig config, Reconciler<?> reconciler) {
        operator.register(reconciler, o -> o.settingNamespace(config.namespace()));
    }

    private KsmcController() {
    }
}
