package io.ksmc.controller.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.MinecraftVersions;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.SoftwareResolver;
import io.ksmc.controller.ControllerConfig;
import io.ksmc.controller.software.BuildJobRunner;
import io.ksmc.controller.software.SoftwareCatalog;
import java.io.IOException;

/** Résolution du logiciel d'un service et suivi de la disponibilité de son artefact. */
public final class ServiceSoftware {

    /** Logiciel résolu pour un service. */
    public record Setup(SoftwareDefinition definition, ResolvedSoftware resolved, int javaVersion, String image) {
        public String family() {
            return definition.getSpec().family.name().toLowerCase();
        }
    }

    public enum Availability { READY, WAITING, FAILED }

    public record ArtifactStatus(Availability availability, String message) {
    }

    private final KubernetesClient client;
    private final SoftwareCatalog catalog;
    private final ArtifactCache cache;
    private final BuildJobRunner builds;
    private final ControllerConfig config;

    public ServiceSoftware(KubernetesClient client, SoftwareCatalog catalog, ArtifactCache cache, BuildJobRunner builds,
            ControllerConfig config) {
        this.client = client;
        this.catalog = catalog;
        this.cache = cache;
        this.builds = builds;
        this.config = config;
    }

    public Setup resolve(CloudService svc) throws ServiceFailureException, IOException {
        String ns = svc.getMetadata().getNamespace();
        var ref = svc.getSpec().software;
        SoftwareDefinition def = client.resources(SoftwareDefinition.class).inNamespace(ns).withName(ref.name).get();
        if (def == null) {
            throw new ServiceFailureException("SoftwareNotFound", "SoftwareDefinition '" + ref.name + "' not found");
        }
        ResolvedSoftware resolved;
        try {
            resolved = catalog.resolve(def, ref.version == null ? "latest" : ref.version);
        } catch (SoftwareResolver.VersionNotFoundException e) {
            throw new ServiceFailureException("SoftwareResolutionFailed", e.getMessage());
        }
        int java = resolved.javaVersion() > 0 ? resolved.javaVersion()
                : MinecraftVersions.javaFor(resolved.version(), def.getSpec().javaVersions, 21);
        String image = ref.image != null ? ref.image : config.runtimeImageFor(java);
        return new Setup(def, resolved, java, image);
    }

    /** Disponibilité d'un téléchargement annexe (mods...) dans le cache partagé. */
    public ArtifactStatus ensureDownload(ArtifactPlan.Download plan) throws IOException {
        ArtifactCache.Status s = cache.ensure(plan);
        return switch (s.state()) {
            case READY -> new ArtifactStatus(Availability.READY, null);
            case DOWNLOADING -> new ArtifactStatus(Availability.WAITING, s.message());
            case FAILED -> new ArtifactStatus(Availability.FAILED, s.message());
        };
    }

    /** Disponibilité de l'artefact ; démarre le téléchargement ou la construction si nécessaire. */
    public ArtifactStatus ensureArtifact(String namespace, Setup setup) throws IOException {
        ArtifactPlan plan = setup.resolved().plan();
        if (plan instanceof ArtifactPlan.Download d) {
            ArtifactCache.Status s = cache.ensure(d);
            return switch (s.state()) {
                case READY -> new ArtifactStatus(Availability.READY, null);
                case DOWNLOADING -> new ArtifactStatus(Availability.WAITING, s.message());
                case FAILED -> new ArtifactStatus(Availability.FAILED, s.message());
            };
        }
        ArtifactPlan.BuildJob job = (ArtifactPlan.BuildJob) plan;
        BuildJobRunner.Status s = builds.ensure(namespace, job);
        return switch (s.state()) {
            case READY -> new ArtifactStatus(Availability.READY, null);
            case BUILDING -> new ArtifactStatus(Availability.WAITING, s.message());
            case FAILED -> new ArtifactStatus(Availability.FAILED, s.message());
        };
    }
}
