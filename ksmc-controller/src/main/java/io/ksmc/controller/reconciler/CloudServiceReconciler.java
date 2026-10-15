package io.ksmc.controller.reconciler;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.model.TemplateRef;
import io.ksmc.controller.messaging.CloudEvents;
import io.ksmc.controller.messaging.Snapshots;
import io.ksmc.controller.service.PhaseCalculator;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.controller.service.PodFactory;
import io.ksmc.controller.service.ServiceFailureException;
import io.ksmc.controller.service.ServiceSoftware;
import io.ksmc.controller.template.Digests;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fait correspondre chaque {@link CloudService} à son Pod (et PVC si statique) et reflète son état réel. */
public final class CloudServiceReconciler implements Reconciler<CloudService> {
    private static final Logger LOG = LoggerFactory.getLogger(CloudServiceReconciler.class);
    static final Duration WAIT = Duration.ofSeconds(10);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final KubernetesClient client;
    private final ServiceSoftware software;
    private final PodFactory pods;
    private final EventRecorder events;
    private final CloudEvents cloudEvents;
    private final Clock clock;
    private final Set<String> secretsEnsured = ConcurrentHashMap.newKeySet();

    public CloudServiceReconciler(KubernetesClient client, ServiceSoftware software, PodFactory pods,
            EventRecorder events, CloudEvents cloudEvents, Clock clock) {
        this.client = client;
        this.software = software;
        this.pods = pods;
        this.events = events;
        this.cloudEvents = cloudEvents;
        this.clock = clock;
    }

    @Override
    public List<EventSource<?, CloudService>> prepareEventSources(EventSourceContext<CloudService> context) {
        var config = InformerEventSourceConfiguration.from(Pod.class, CloudService.class)
                .withLabelSelector(Ksmc.LABEL_MANAGED_BY + "=" + Ksmc.MANAGED_BY)
                .withNamespacesInheritedFromController()
                .build();
        return List.of(new InformerEventSource<>(config));
    }

    @Override
    public UpdateControl<CloudService> reconcile(CloudService svc, Context<CloudService> context) {
        return reconcile(svc, context.getSecondaryResource(Pod.class).orElse(null));
    }

    /** Réconciliation avec le Pod courant (séparée du contexte JOSDK pour les tests). */
    UpdateControl<CloudService> reconcile(CloudService svc, Pod pod) {
        CloudServiceStatus status = svc.getStatus() == null ? new CloudServiceStatus() : svc.getStatus();
        Phase before = status.phase;
        Duration reschedule = null;
        try {
            ensureForwardingSecret(svc.getMetadata().getNamespace());
            reschedule = sync(svc, pod, status);
        } catch (ServiceFailureException e) {
            fail(status, e.reason(), e.getMessage());
        } catch (IOException | KubernetesClientException e) {
            LOG.warn("Service {} : {}", svc.getMetadata().getName(), e.getMessage());
            status.reason = "TemporaryError";
            status.message = e.getMessage();
            if (status.phase == null) {
                status.phase = Phase.Pending;
            }
            reschedule = Duration.ofSeconds(30);
        }
        finish(svc, status, before);
        svc.setStatus(status);
        UpdateControl<CloudService> control = UpdateControl.patchStatus(svc);
        return reschedule == null ? control : control.rescheduleAfter(reschedule);
    }

    private Duration sync(CloudService svc, Pod pod, CloudServiceStatus status)
            throws ServiceFailureException, IOException {
        CloudServiceSpec spec = svc.getSpec();
        if (spec.desiredState == CloudServiceSpec.DesiredState.Stopped) {
            if (pod != null && pod.getMetadata().getDeletionTimestamp() == null) {
                client.pods().inNamespace(svc.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).delete();
                set(status, Phase.Stopping, "StopRequested", null);
            } else if (pod == null) {
                set(status, Phase.Stopped, "StoppedByUser", null);
                status.address = null;
            }
            return null;
        }
        if (pod == null) {
            return createPodIfAllowed(svc, status);
        }
        PhaseCalculator.Result r = PhaseCalculator.compute(pod);
        set(status, r.phase(), r.reason(), r.message());
        status.podName = pod.getMetadata().getName();
        status.address = r.ready() && pod.getStatus() != null && pod.getStatus().getPodIP() != null
                ? pod.getStatus().getPodIP() + ":" + PodFactory.MINECRAFT_PORT : null;
        if (r.ready() && status.readyAt == null) {
            status.readyAt = clock.instant().toString();
        }
        if (r.terminated()) {
            return handleTermination(svc, pod, status, r);
        }
        return r.phase() == Phase.Running ? null : WAIT;
    }

    /** Process terminé : redémarrage selon {@code restartPolicy}, avec délai croissant. */
    private Duration handleTermination(CloudService svc, Pod pod, CloudServiceStatus status, PhaseCalculator.Result r) {
        if (status.lastExitAt == null) {
            status.lastExitAt = clock.instant().toString();
        }
        boolean restart = switch (svc.getSpec().restartPolicy) {
            case Always -> true;
            case OnFailure -> r.phase() == Phase.Failed;
            case Never -> false;
        };
        if (!restart) {
            return null;
        }
        int restarts = status.restartCount == null ? 0 : status.restartCount;
        Duration delay = backoff(restarts);
        Instant at = Instant.parse(status.lastExitAt).plus(delay);
        if (clock.instant().isBefore(at)) {
            return Duration.between(clock.instant(), at);
        }
        client.pods().inNamespace(svc.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).delete();
        status.restartCount = restarts + 1;
        status.lastExitAt = null;
        events.normal(svc, "Restarting", "restart #" + status.restartCount + " after " + r.reason());
        return WAIT;
    }

    /** Référence propriétaire « controller » : le Pod est supprimé avec le service. */
    static OwnerReference ownerReference(HasMetadata owner) {
        return new OwnerReferenceBuilder().withApiVersion(owner.getApiVersion()).withKind(owner.getKind())
                .withName(owner.getMetadata().getName()).withUid(owner.getMetadata().getUid())
                .withController(true).withBlockOwnerDeletion(true).build();
    }

    static Duration backoff(int restarts) {
        long seconds = 10L << Math.min(restarts, 5);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private Duration createPodIfAllowed(CloudService svc, CloudServiceStatus status)
            throws ServiceFailureException, IOException {
        // Un Pod terminé puis supprimé par la politique de redémarrage, ou disparu (nœud perdu, kubectl delete) :
        // on le recrée, sauf si le service est terminé avec la politique Never.
        if ((status.phase == Phase.Failed || status.phase == Phase.Stopped)
                && svc.getSpec().restartPolicy == CloudServiceSpec.RestartPolicy.Never && status.podName != null) {
            return null;
        }
        String ns = svc.getMetadata().getNamespace();
        ServiceSoftware.Setup setup = software.resolve(svc);
        status.softwareVersion = setup.resolved().build() == null ? setup.resolved().version()
                : setup.resolved().version() + "@" + setup.resolved().build();
        status.javaVersion = setup.javaVersion();
        status.image = setup.image();
        ServiceSoftware.ArtifactStatus artifact = software.ensureArtifact(ns, setup);
        switch (artifact.availability()) {
            case WAITING -> {
                set(status, Phase.Preparing, "PreparingSoftware", artifact.message());
                return WAIT;
            }
            case FAILED -> throw new ServiceFailureException("SoftwareDownloadFailed", artifact.message());
            default -> { }
        }
        if (svc.getSpec().staticService) {
            ensurePvc(svc);
        }
        String hash = Digests.sha256(Serialization.asJson(svc.getSpec())).substring(0, 16);
        Pod pod = pods.build(svc, setup, imageTemplates(svc), hash);
        pod.getMetadata().setOwnerReferences(List.of(ownerReference(svc)));
        try {
            client.pods().inNamespace(ns).resource(pod).create();
            events.normal(svc, "PodCreated", "pod " + pod.getMetadata().getName() + " (" + setup.image() + ")");
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {
                throw e;
            }
        }
        status.podName = pod.getMetadata().getName();
        status.readyAt = null;
        status.startedAt = clock.instant().toString();
        set(status, Phase.Preparing, "Preparing", null);
        return WAIT;
    }

    private List<PodFactory.ImageTemplate> imageTemplates(CloudService svc) {
        List<PodFactory.ImageTemplate> out = new ArrayList<>();
        List<TemplateRef> refs = svc.getSpec().templates;
        for (int i = 0; i < refs.size(); i++) {
            ServiceTemplate t = client.resources(ServiceTemplate.class).inNamespace(svc.getMetadata().getNamespace())
                    .withName(refs.get(i).name).get();
            if (t != null && t.getSpec().image != null) {
                out.add(new PodFactory.ImageTemplate(i, t.getSpec().image.image, t.getSpec().image.path));
            }
        }
        return out;
    }

    private void ensurePvc(CloudService svc) {
        String ns = svc.getMetadata().getNamespace();
        String name = PodFactory.pvcName(svc);
        if (client.persistentVolumeClaims().inNamespace(ns).withName(name).get() != null) {
            return;
        }
        var storage = svc.getSpec().storage;
        var pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(name)
                    .addToLabels(Ksmc.LABEL_SERVICE, svc.getMetadata().getName())
                    .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY)
                .endMetadata()
                .withNewSpec().withAccessModes("ReadWriteOnce")
                    .withStorageClassName(storage == null ? null : storage.storageClassName)
                    .withNewResources().addToRequests("storage", new Quantity(storage == null ? "2Gi" : storage.size))
                    .endResources()
                .endSpec().build();
        client.persistentVolumeClaims().inNamespace(ns).resource(pvc).create();
        events.normal(svc, "VolumeCreated", "persistent volume claim " + name);
    }

    /** Secret partagé du forwarding moderne (Velocity ↔ serveurs), créé une fois par namespace. */
    private void ensureForwardingSecret(String ns) {
        if (secretsEnsured.contains(ns)) {
            return;
        }
        if (client.secrets().inNamespace(ns).withName(PodFactory.FORWARDING_SECRET).get() == null) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            try {
                client.secrets().inNamespace(ns).resource(new SecretBuilder()
                        .withNewMetadata().withName(PodFactory.FORWARDING_SECRET)
                            .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY).endMetadata()
                        .withStringData(Map.of("secret", secret)).build()).create();
            } catch (KubernetesClientException e) {
                if (e.getCode() != 409) {
                    throw e;
                }
            }
        }
        secretsEnsured.add(ns);
    }

    /** Événements cloud d'un changement de phase (le statut est déjà à jour dans {@code svc}). */
    private void publishPhaseEvents(CloudService svc, CloudServiceStatus status, Phase before) {
        svc.setStatus(status);
        if (before == null) {
            cloudEvents.publish(Snapshots.service("service.created", svc));
        }
        CloudEvent changed = Snapshots.service("service.phase-changed", svc);
        changed.data.getAsJsonObject().addProperty("from", before == null ? null : before.name());
        changed.data.getAsJsonObject().addProperty("to", status.phase.name());
        cloudEvents.publish(changed);
        if (status.phase == Phase.Running) {
            cloudEvents.publish(Snapshots.service("service.ready", svc));
        } else if (status.phase == Phase.Stopped || status.phase == Phase.Failed) {
            cloudEvents.publish(Snapshots.service("service.stopped", svc));
        }
    }

    private static void set(CloudServiceStatus status, Phase phase, String reason, String message) {
        status.phase = phase;
        status.reason = reason;
        status.message = message;
    }

    private void fail(CloudServiceStatus status, String reason, String message) {
        set(status, Phase.Failed, reason, message);
    }

    private void finish(CloudService svc, CloudServiceStatus status, Phase before) {
        long generation = svc.getMetadata().getGeneration() == null ? 0 : svc.getMetadata().getGeneration();
        status.observedGeneration = generation;
        if (status.onlinePlayers != null && status.maxPlayers != null) {
            status.players = status.onlinePlayers + "/" + status.maxPlayers;
        }
        Conditions.set(status, generation, Conditions.READY, status.phase == Phase.Running,
                status.reason == null ? "Unknown" : status.reason, status.message);
        if (status.phase != before) {
            String msg = (before == null ? "-" : before) + " → " + status.phase
                    + (status.message == null ? "" : " (" + status.message + ")");
            if (status.phase == Phase.Failed) {
                events.warning(svc, status.reason, msg);
            } else {
                events.normal(svc, "Phase" + status.phase, msg);
            }
            LOG.info("Service {} : {}", svc.getMetadata().getName(), msg);
            publishPhaseEvents(svc, status, before);
        }
    }
}
