package io.ksmc.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.spi.ScalingStrategy;
import io.ksmc.api.spi.ServicePodCustomizer;
import io.ksmc.api.spi.SoftwareResolver;
import io.ksmc.api.spi.TemplateSourceProvider;
import io.ksmc.controller.bridge.BridgeHub;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.health.KubernetesHealthCheck;
import io.ksmc.controller.http.HttpServer;
import io.ksmc.controller.http.ModuleApi;
import io.ksmc.controller.http.StaticTokenAuthenticator;
import io.ksmc.controller.internal.InternalApi;
import io.ksmc.controller.internal.PodAuthenticator;
import io.ksmc.controller.messaging.NatsConnector;
import io.ksmc.controller.module.DefaultModuleContext;
import io.ksmc.controller.module.ModuleHost;
import io.ksmc.controller.module.ModuleSources;
import io.ksmc.controller.reconciler.CloudServiceReconciler;
import io.ksmc.controller.reconciler.EventRecorder;
import io.ksmc.controller.reconciler.KsmcModuleReconciler;
import io.ksmc.controller.reconciler.ServiceGroupReconciler;
import io.ksmc.controller.reconciler.ServiceTaskReconciler;
import io.ksmc.controller.reconciler.SoftwareDefinitionReconciler;
import io.ksmc.controller.service.ArtifactCache;
import io.ksmc.controller.service.ModrinthMods;
import io.ksmc.controller.service.PodFactory;
import io.ksmc.controller.service.ServiceManifestBuilder;
import io.ksmc.controller.service.ServiceSoftware;
import io.ksmc.controller.software.BuildJobRunner;
import io.ksmc.controller.software.JdkResolverContext;
import io.ksmc.controller.software.SoftwareCatalog;
import io.ksmc.controller.storage.FileArtifactStorage;
import io.ksmc.controller.task.ScalingStrategies;
import io.ksmc.controller.template.ConfigMapTemplateSource;
import io.ksmc.controller.template.GitTemplateSource;
import io.ksmc.controller.template.HttpTemplateSource;
import io.ksmc.controller.template.StorageTemplateSource;
import io.ksmc.controller.template.TemplateResolver;
import io.ksmc.wire.messaging.MessagingTransport;
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

        FileArtifactStorage storage = new FileArtifactStorage(Path.of(config.dataDir()));
        HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        // Extensions des modules (SPI) : reliées aux registres des consommateurs.
        ExtensionRegistry extensions = new ExtensionRegistry(e -> LOG.warn("Module : {}", e.toString()));
        SoftwareCatalog catalog = new SoftwareCatalog(new JdkResolverContext(), clock);
        extensions.bind(SoftwareResolver.class, catalog::register, catalog::unregister);
        TemplateResolver templates = new TemplateResolver(List.of(new StorageTemplateSource(), new GitTemplateSource(),
                new HttpTemplateSource(httpClient), new ConfigMapTemplateSource()));
        extensions.bind(TemplateSourceProvider.class, templates::register, templates::unregister);
        ScalingStrategies scaling = new ScalingStrategies();
        extensions.bind(ScalingStrategy.class, scaling::register, scaling::unregister);
        ArtifactCache artifacts = new ArtifactCache(storage, httpClient);
        ServiceSoftware software = new ServiceSoftware(client, catalog, artifacts,
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
        register(operator, config, new CloudServiceReconciler(client, software,
                new PodFactory(config, () -> extensions.all(ServicePodCustomizer.class)), events, nats, bridges, clock));
        register(operator, config, new ServiceTaskReconciler(client, scaling, events, nats, bridges, clock));
        register(operator, config, new ServiceGroupReconciler());

        // Modules : chaque module voit l'API publique, son registre et la messagerie du controller.
        Path dataDir = Path.of(config.dataDir());
        ModuleHost modules = new ModuleHost(extensions, (descriptor, scope, moduleConfig, loader) ->
                new DefaultModuleContext(descriptor, scope, moduleConfig, loader, client, config.namespace(),
                        () -> {
                            // Transport fourni par un module (ex. Redis) prioritaire sur NATS pour les modules.
                            List<MessagingTransport> transports = extensions.all(MessagingTransport.class);
                            return transports.isEmpty() ? nats.messaging() : transports.getFirst();
                        }, dataDir.resolve("module-data")));
        ModuleSources moduleSources = new ModuleSources(storage, artifacts, httpClient, dataDir.resolve("module-jars"),
                Path.of(config.builtinModulesDir()));
        KsmcModuleReconciler moduleReconciler = new KsmcModuleReconciler(client, config.namespace(), modules, moduleSources);
        register(operator, config, moduleReconciler);
        Thread.ofVirtual().name("ksmc-module-sweep").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    return;
                }
                moduleReconciler.sweep();
            }
        });
        operator.start();

        InternalApi internal = new InternalApi(new PodAuthenticator(client, clock), client, storage, manifests,
                config.bridgesDir());
        ModuleApi moduleApi = new ModuleApi(extensions, new StaticTokenAuthenticator(config.apiToken()));
        HttpServer http = new HttpServer(health, List.of(internal, moduleApi)).start(config.httpPort());
        LOG.info("HTTP prêt sur le port {}", http.port());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Arrêt du controller");
            http.close();
            operator.stop();
            modules.disableAll();
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
