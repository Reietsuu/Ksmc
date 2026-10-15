package io.ksmc.controller.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.model.TemplateRef;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.internal.ManifestProvider;
import io.ksmc.controller.template.TemplateResolver;
import io.ksmc.wire.manifest.ServiceManifest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Construit le {@link ServiceManifest} d'un service à la demande de son Pod. */
public final class ServiceManifestBuilder implements ManifestProvider {
    /** Montage du secret de forwarding dans les Pods. */
    public static final String FORWARDING_SECRET_FILE = "/var/run/ksmc/forwarding/secret";
    /** Dossier des templates de type image copiés par les initContainers. */
    public static final String IMAGE_TEMPLATES_DIR = "/ksmc/image-templates";
    private static final Set<String> NOGUI_FAMILIES = Set.of("paper", "spigot", "fabric", "generic");

    private final KubernetesClient client;
    private final ServiceSoftware software;
    private final TemplateResolver templates;
    private final ArtifactStorage storage;
    private final ControllerConfig config;

    public ServiceManifestBuilder(KubernetesClient client, ServiceSoftware software, TemplateResolver templates,
            ArtifactStorage storage, ControllerConfig config) {
        this.client = client;
        this.software = software;
        this.templates = templates;
        this.storage = storage;
        this.config = config;
    }

    @Override
    public Optional<Object> manifest(String namespace, String name) throws Exception {
        CloudService svc = client.resources(CloudService.class).inNamespace(namespace).withName(name).get();
        return svc == null ? Optional.empty() : Optional.of(build(svc));
    }

    public ServiceManifest build(CloudService svc) throws Exception {
        String ns = svc.getMetadata().getNamespace();
        CloudServiceSpec spec = svc.getSpec();
        ServiceSoftware.Setup setup = software.resolve(svc);
        ServiceSoftware.ArtifactStatus artifact = software.ensureArtifact(ns, setup);
        switch (artifact.availability()) {
            case WAITING -> throw new RetryLaterException(artifact.message(), 10);
            case FAILED -> throw new ServiceFailureException("SoftwareDownloadFailed", artifact.message());
            default -> { }
        }

        ServiceManifest m = new ServiceManifest();
        m.namespace = ns;
        m.service = svc.getMetadata().getName();
        m.task = spec.task;
        m.environment = spec.environment == Environment.PROXY ? "proxy" : "server";
        m.family = setup.family();
        m.softwareVersion = setup.resolved().version();
        m.softwareBuild = setup.resolved().build();
        m.staticService = spec.staticService;
        m.acceptEula = config.acceptEula();
        m.properties.putAll(spec.properties);

        ArtifactPlan plan = setup.resolved().plan();
        ServiceManifest.Artifact a = new ServiceManifest.Artifact();
        a.url = "/internal/v1/files/" + ArtifactCache.storageKey(plan);
        if (plan instanceof ArtifactPlan.Download d) {
            a.target = d.target();
        } else {
            a.extract = true;
        }
        m.artifacts.add(a);

        addTemplates(svc, m);
        network(svc, setup, m);
        launch(svc, setup, m);

        if (spec.deployOnStop != null) {
            m.deployOnStop = new ServiceManifest.Deploy();
            m.deployOnStop.url = "/internal/v1/services/" + ns + "/" + m.service + "/deploy?template="
                    + ArtifactStorage.normalize(spec.deployOnStop.template);
            m.deployOnStop.excludes.addAll(spec.deployOnStop.excludes);
        }
        return m;
    }

    private void addTemplates(CloudService svc, ServiceManifest m) throws ServiceFailureException {
        String ns = svc.getMetadata().getNamespace();
        TemplateContext ctx = new TemplateContext(ns, client, storage);
        List<TemplateRef> refs = svc.getSpec().templates;
        for (int i = 0; i < refs.size(); i++) {
            TemplateRef ref = refs.get(i);
            boolean optional = Boolean.TRUE.equals(ref.optional);
            ServiceTemplate tpl = client.resources(ServiceTemplate.class).inNamespace(ns).withName(ref.name).get();
            if (tpl == null) {
                if (optional) {
                    continue;
                }
                throw new ServiceFailureException("TemplateUnavailable", "ServiceTemplate '" + ref.name + "' not found");
            }
            ServiceManifest.Template t = new ServiceManifest.Template();
            t.name = ref.name;
            t.optional = optional;
            if (tpl.getSpec().image != null) {
                t.localPath = IMAGE_TEMPLATES_DIR + "/" + i;
            } else {
                try {
                    t.url = "/internal/v1/files/" + templates.resolve(tpl, ctx).key();
                } catch (IOException | RuntimeException e) {
                    if (optional) {
                        continue;
                    }
                    throw new ServiceFailureException("TemplateUnavailable", ref.name + ": " + e.getMessage());
                }
            }
            m.templates.add(t);
        }
    }

    private void network(CloudService svc, ServiceSoftware.Setup setup, ServiceManifest m) {
        ServiceManifest.NetworkConfig net = m.network;
        net.forwardingSecretFile = FORWARDING_SECRET_FILE;
        String override = svc.getSpec().bridge == null ? null : svc.getSpec().bridge.forwarding;
        ServiceTask task = svc.getSpec().task == null ? null : client.resources(ServiceTask.class)
                .inNamespace(svc.getMetadata().getNamespace()).withName(svc.getSpec().task).get();
        if (svc.getSpec().environment == Environment.PROXY) {
            net.forwarding = override != null ? override : "bungeecord".equals(m.family) ? "legacy" : "modern";
            if (task != null && task.getSpec().expose != null) {
                net.proxyProtocol = task.getSpec().expose.proxyProtocol;
                net.maxPlayers = task.getSpec().expose.maxPlayers;
            }
        } else {
            net.forwarding = override != null ? override : namespaceForwarding(svc.getMetadata().getNamespace(), m.family);
        }
    }

    /**
     * Forwarding attendu par les proxies du namespace : {@code modern} si un proxy Velocity (non legacy) existe,
     * sinon {@code legacy} si un proxy existe, sinon {@code none}. Forge/NeoForge : {@code none} par défaut
     * (dépend du mod de forwarding installé par l'utilisateur, réglable par {@code bridge.forwarding}).
     */
    String namespaceForwarding(String ns, String family) {
        if ("forge".equals(family) || "neoforge".equals(family)) {
            return "none";
        }
        String mode = "none";
        for (CloudService proxy : client.resources(CloudService.class).inNamespace(ns)
                .withLabel(Ksmc.LABEL_ENVIRONMENT, "proxy").list().getItems()) {
            String forced = proxy.getSpec().bridge == null ? null : proxy.getSpec().bridge.forwarding;
            SoftwareDefinition def = client.resources(SoftwareDefinition.class).inNamespace(ns)
                    .withName(proxy.getSpec().software.name).get();
            boolean velocity = def != null && def.getSpec().family.name().equals("VELOCITY");
            String proxyMode = forced != null ? forced : velocity ? "modern" : "legacy";
            if ("modern".equals(proxyMode)) {
                return "modern";
            }
            if ("legacy".equals(proxyMode)) {
                mode = "legacy";
            }
        }
        return mode;
    }

    private void launch(CloudService svc, ServiceSoftware.Setup setup, ServiceManifest m) {
        CloudServiceSpec spec = svc.getSpec();
        var defLaunch = setup.definition().getSpec().launch;
        ServiceManifest.Launch l = m.launch;
        l.jvmOptions.add("-Xms" + spec.memoryMb + "M");
        l.jvmOptions.add("-Xmx" + spec.memoryMb + "M");
        if (defLaunch != null) {
            l.jvmOptions.addAll(defLaunch.jvmOptions);
        }
        l.jvmOptions.addAll(spec.jvmOptions);
        l.args.addAll(setup.resolved().launch());
        if (NOGUI_FAMILIES.contains(m.family) && !l.args.contains("nogui")) {
            l.args.add("nogui");
        }
        if (defLaunch != null) {
            l.args.addAll(defLaunch.args);
        }
        l.args.addAll(spec.processArgs);
        l.stopCommand = defLaunch != null && defLaunch.stopCommand != null ? defLaunch.stopCommand
                : "bungeecord".equals(m.family) ? "end" : "stop";
        l.stopTimeoutSeconds = spec.stopTimeoutSeconds;
    }
}
