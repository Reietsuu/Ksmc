package io.ksmc.controller.reconciler;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleSpec;
import io.ksmc.api.model.KsmcModuleStatus;
import io.ksmc.api.model.KsmcModuleStatus.Phase;
import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.controller.module.ModuleException;
import io.ksmc.controller.module.ModuleHost;
import io.ksmc.controller.module.ModuleSources;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installe, configure, recharge et désactive les modules décrits par les ressources {@link KsmcModule}.
 * Le nom de la ressource est l'id du module. Pas de finalizer : un module dont la ressource disparaît est désactivé
 * par {@link #sweep()}.
 */
public final class KsmcModuleReconciler implements Reconciler<KsmcModule> {

    private static final Logger LOG = LoggerFactory.getLogger(KsmcModuleReconciler.class);
    /** Vérification périodique : réactive un module désactivé en cascade (rechargement d'une dépendance). */
    static final Duration RECHECK = Duration.ofSeconds(30);
    static final Duration DOWNLOAD_POLL = Duration.ofSeconds(2);
    static final Duration DEPENDENCY_RETRY = Duration.ofSeconds(10);
    static final Duration ERROR_RETRY = Duration.ofMinutes(1);
    /** Échecs qui ne changent qu'avec la ressource elle-même (nouvelle génération). */
    static final Set<String> PERMANENT = Set.of(ModuleException.CHECKSUM_MISMATCH, ModuleException.INCOMPATIBLE_API,
            ModuleException.INVALID_DESCRIPTOR, "UnsupportedType");

    private final KubernetesClient client;
    private final String namespace;
    private final ModuleHost host;
    private final ModuleSources sources;
    /** Empreinte de la source chargée, par module. */
    private final Map<String, String> loadedDigests = new ConcurrentHashMap<>();

    public KsmcModuleReconciler(KubernetesClient client, String namespace, ModuleHost host, ModuleSources sources) {
        this.client = client;
        this.namespace = namespace;
        this.host = host;
        this.sources = sources;
    }

    @Override
    public UpdateControl<KsmcModule> reconcile(KsmcModule module, Context<KsmcModule> context) {
        return reconcile(module);
    }

    UpdateControl<KsmcModule> reconcile(KsmcModule module) {
        String id = module.getMetadata().getName();
        KsmcModuleSpec spec = module.getSpec();
        KsmcModuleStatus status = module.getStatus() == null ? new KsmcModuleStatus() : module.getStatus();
        String before = Serialization.asJson(status);
        long generation = module.getMetadata().getGeneration() == null ? 0 : module.getMetadata().getGeneration();
        Duration next = null;
        synchronized (host) {
            try {
                if (spec.type == KsmcModuleSpec.Type.Container) {
                    throw new ModuleException("UnsupportedType", "les modules conteneurs ne sont pas encore pris en charge");
                }
                if (!spec.enabled) {
                    disable(id);
                    set(status, Phase.Disabled, "Disabled", null);
                } else {
                    next = enable(id, spec, status);
                }
            } catch (ModuleException e) {
                if (host.descriptor(id).isEmpty()) {
                    loadedDigests.remove(id);
                }
                LOG.warn("Module {} : {} ({})", id, e.getMessage(), e.reason());
                set(status, Phase.Failed, e.reason(), e.getMessage());
                next = PERMANENT.contains(e.reason()) ? null
                        : ModuleException.MISSING_DEPENDENCY.equals(e.reason()) ? DEPENDENCY_RETRY : ERROR_RETRY;
            }
        }
        Conditions.set(status, generation, Conditions.READY, status.phase == Phase.Enabled, status.reason, status.message);
        status.observedGeneration = generation;
        module.setStatus(status);
        UpdateControl<KsmcModule> control = Serialization.asJson(status).equals(before)
                ? UpdateControl.noUpdate() : UpdateControl.patchStatus(module);
        return next == null ? control : control.rescheduleAfter(next);
    }

    private Duration enable(String id, KsmcModuleSpec spec, KsmcModuleStatus status) throws ModuleException {
        ModuleSources.Outcome outcome = sources.resolve(id, spec.source);
        if (outcome instanceof ModuleSources.Pending p) {
            set(status, Phase.Pending, "Downloading", p.message());
            return DOWNLOAD_POLL;
        }
        ModuleSources.Ready ready = (ModuleSources.Ready) outcome;
        JsonNode config = spec.config;
        boolean active = host.descriptor(id).isPresent();
        if (active && ready.digest().equals(loadedDigests.get(id))) {
            host.configure(id, config);
        } else {
            ModuleHost.EnableResult result = host.enable(ready.jar(), config, id);
            loadedDigests.put(id, ready.digest());
            if (!result.disabledDependents().isEmpty()) {
                LOG.info("Module {} rechargé ; dépendants à réactiver : {}", id, result.disabledDependents());
                result.disabledDependents().forEach(loadedDigests::remove);
            }
        }
        ModuleDescriptor d = host.descriptor(id).orElseThrow();
        status.moduleId = d.id;
        status.loadedVersion = d.version;
        set(status, Phase.Enabled, "Enabled", null);
        return RECHECK;
    }

    private void disable(String id) {
        for (String dependent : host.disable(id)) {
            loadedDigests.remove(dependent);
        }
        loadedDigests.remove(id);
    }

    private static void set(KsmcModuleStatus status, Phase phase, String reason, String message) {
        status.phase = phase;
        status.reason = reason;
        status.message = message;
    }

    /** Désactive les modules actifs dont la ressource n'existe plus. */
    public void sweep() {
        Set<String> existing;
        try {
            existing = client.resources(KsmcModule.class).inNamespace(namespace).list().getItems().stream()
                    .map(m -> m.getMetadata().getName()).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            LOG.debug("Liste des modules indisponible : {}", e.getMessage());
            return;
        }
        synchronized (host) {
            for (String id : host.activeVersions().keySet()) {
                if (!existing.contains(id) && host.descriptor(id).isPresent()) {
                    LOG.info("Ressource du module {} supprimée", id);
                    disable(id);
                }
            }
        }
    }
}
