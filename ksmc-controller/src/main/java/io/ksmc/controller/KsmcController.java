package io.ksmc.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.ksmc.controller.bridge.BridgeHub;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.health.KubernetesHealthCheck;
import io.ksmc.controller.http.HttpServer;
import io.ksmc.controller.internal.InternalApi;
import io.ksmc.controller.internal.PodAuthenticator;
import io.ksmc.controller.messaging.NatsConnector;
import io.ksmc.controller.reconciler.CloudServiceReconciler;
import io.ksmc.controller.reconciler.EventRecorder;
import io.ksmc.controller.reconciler.ServiceGroupReconciler;
import io.ksmc.controller.reconciler.ServiceTaskReconciler;
import io.ksmc.controller.reconciler.SoftwareDefinitionReconciler;
import io.ksmc.controller.task.ScalingStrategies;
import io.ksmc.controller.service.ArtifactCache;
import io.ksmc.controller.service.ModrinthMods;
import io.ksmc.controller.service.PodFactory;
import io.ksmc.controller.service.ServiceManifestBuilder;
import io.ksmc.controller.service.ServiceSoftware;
import io.ksmc.controller.software.BuildJobRunner;
import io.ksmc.controller.software.JdkResolverContext;
import io.ksmc.controller.software.SoftwareCatalog;
import io.ksmc.controller.storage.FileArtifactStorage;
import io.ksmc.controller.template.ConfigMapTemplateSource;
import io.ksmc.controller.template.GitTemplateSource;
import io.ksmc.controller.template.HttpTemplateSource;
import io.ksmc.controller.template.StorageTemplateSource;
import io.ksmc.controller.template.TemplateResolver;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
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

        FileArtifactStorage storage = new FileArtifactStorage(Path.of(System.getenv().getOrDefault("KSMC_DATA_DIR", "/data")));
        HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        SoftwareCatalog catalog = new SoftwareCatalog(new JdkResolverContext(), clock);
        TemplateResolver templates = new TemplateResolver(List.of(new StorageTemplateSource(), new GitTemplateSource(),
                new HttpTemplateSource(httpClient), new ConfigMapTemplateSource()));
        ServiceSoftware software = new ServiceSoftware(client, catalog, new ArtifactCache(storage, httpClient),
                new BuildJobRunner(client, storage, config), config);
        ServiceManifestBuilder manifests = new ServiceManifestBuilder(client, software, templates, storage, config)
                .withMods(new ModrinthMods(new JdkResolverContext(), clock));
        EventRecorder events = new EventRecorder(client);
        BridgeHub bridges = new BridgeHub(client, config.namespace(), nats, clock);
        nats.onReady(m -> {
            try {
                bridges.attach(m);
            } catch (Exception e) {
                throw new IllegalStateException("bridge hub: " + e.getMessage(), e);
            }
        });
        nats.connectAsync();

        Operator operator = new Operator(o -> o.withKubernetesClient(client).withUseSSAToPatchPrimaryResource(false));
        register(operator, config, new SoftwareDefinitionReconciler(catalog));
        register(operator, config, new CloudServiceReconciler(client, software, new PodFactory(config), events, nats, bridges, clock));
        register(operator, config, new ServiceTaskReconciler(client, new ScalingStrategies(), events, nats, bridges, clock));
        register(operator, config, new ServiceGroupReconciler());
        operator.start();

        InternalApi internal = new InternalApi(new PodAuthenticator(client, clock), client, storage, manifests,
                config.bridgesDir());
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
