package io.ksmc.controller.reconciler;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.model.ServiceTaskStatus;
import io.ksmc.api.spi.ScalingStrategy;
import io.ksmc.controller.messaging.CloudEvents;
import io.ksmc.controller.messaging.Snapshots;
import io.ksmc.controller.task.EffectiveConfig;
import io.ksmc.controller.task.ScalingStrategies;
import io.ksmc.controller.task.ServiceSelection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintient les {@link CloudService} d'une {@link ServiceTask} : nombre, noms, remplacements et mises à jour. */
public final class ServiceTaskReconciler implements Reconciler<ServiceTask> {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceTaskReconciler.class);
    public static final String DEGRADED = "Degraded";
    public static final String PROGRESSING = "Progressing";
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    static final int FAILURE_THRESHOLD = 5;
    static final Duration RESYNC = Duration.ofSeconds(30);

    private final KubernetesClient client;
    private final ScalingStrategies strategies;
    private final EventRecorder events;
    private final CloudEvents cloudEvents;
    private final Clock clock;

    public ServiceTaskReconciler(KubernetesClient client, ScalingStrategies strategies, EventRecorder events,
            CloudEvents cloudEvents, Clock clock) {
        this.client = client;
        this.strategies = strategies;
        this.events = events;
        this.cloudEvents = cloudEvents;
        this.clock = clock;
    }

    @Override
    public List<EventSource<?, ServiceTask>> prepareEventSources(EventSourceContext<ServiceTask> context) {
        var services = InformerEventSourceConfiguration.from(CloudService.class, ServiceTask.class)
                .withNamespacesInheritedFromController().build();
        // Un groupe modifié concerne potentiellement toutes les tasks de son namespace.
        var groups = InformerEventSourceConfiguration.from(ServiceGroup.class, ServiceTask.class)
                .withNamespacesInheritedFromController()
                .withSecondaryToPrimaryMapper(group -> client.resources(ServiceTask.class)
                        .inNamespace(group.getMetadata().getNamespace()).list().getItems().stream()
                        .map(ResourceID::fromResource).collect(Collectors.toSet()))
                .build();
        return List.of(new InformerEventSource<>(services), new InformerEventSource<>(groups));
    }

    @Override
    public UpdateControl<ServiceTask> reconcile(ServiceTask task, Context<ServiceTask> context) {
        return reconcile(task, new ArrayList<>(context.getSecondaryResources(CloudService.class)));
    }

    UpdateControl<ServiceTask> reconcile(ServiceTask task, List<CloudService> services) {
        String ns = task.getMetadata().getNamespace();
        String name = task.getMetadata().getName();
        ServiceTaskStatus status = task.getStatus() == null ? new ServiceTaskStatus() : task.getStatus();
        long generation = task.getMetadata().getGeneration() == null ? 0 : task.getMetadata().getGeneration();
        Instant now = clock.instant();
        Duration reschedule = RESYNC;

        handleStartRequests(task);
        List<ServiceGroup> groups = client.resources(ServiceGroup.class).inNamespace(ns).list().getItems();
        EffectiveConfig effective;
        try {
            Map<String, ServiceGroup> byName = new LinkedHashMap<>();
            groups.forEach(g -> byName.put(g.getMetadata().getName(), g));
            effective = EffectiveConfig.resolve(task, byName);
        } catch (EffectiveConfig.GroupNotFoundException e) {
            Conditions.set(status, generation, Conditions.READY, false, "GroupNotFound", e.getMessage());
            status.observedGeneration = generation;
            task.setStatus(status);
            return UpdateControl.patchStatus(task).rescheduleAfter(RESYNC);
        }
        String previousHash = status.effective == null ? null : status.effective.configHash;
        if (previousHash != null && !previousHash.equals(effective.hash())) {
            cloudEvents.publish(Snapshots.resource("task.updated", task, name));
        }
        status.effective = new ServiceTaskStatus.Effective();
        status.effective.groups = effective.groups();
        status.effective.templates = effective.templateNames();
        status.effective.configHash = effective.hash();

        // Classement des services existants.
        List<CloudService> active = new ArrayList<>();
        List<String> allNames = new ArrayList<>();
        for (CloudService svc : services) {
            allNames.add(svc.getMetadata().getName());
            if (svc.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            Phase phase = svc.getStatus() == null ? null : svc.getStatus().phase;
            boolean stopRequested = svc.getSpec().desiredState == CloudServiceSpec.DesiredState.Stopped;
            if (!svc.getSpec().staticService) {
                if (phase == Phase.Failed) {
                    recordFailure(status, svc, now);
                    delete(svc);
                    continue;
                }
                if (phase == Phase.Stopped || (stopRequested && phase == Phase.Stopping)) {
                    if (phase == Phase.Stopped) {
                        delete(svc);
                    }
                    continue;
                }
                if (stopRequested) {
                    continue;
                }
            } else if (stopRequested) {
                continue;
            }
            if (phase == Phase.Running) {
                resetFailures(status, generation);
            }
            active.add(svc);
        }

        ServiceTaskSpec spec = task.getSpec();
        int target = spec.enabled ? strategies.target(new ScalingStrategy.Context(task, active, now)) : 0;

        // Mise à jour de configuration des services existants.
        reschedule = min(reschedule, applyUpdateStrategy(task, effective, active, status, generation, now));

        // Réduction.
        if (active.size() > target) {
            for (CloudService svc : ServiceSelection.toStop(name, active, active.size() - target)) {
                stop(svc, spec.enabled ? "ScaledDown" : "TaskDisabled");
                active.remove(svc);
            }
        }
        // Augmentation (avec backoff si la task échoue en boucle).
        int missing = target - active.size();
        int created = 0;
        if (missing > 0) {
            Instant notBefore = status.failureWindow == null || status.failureWindow.nextAttemptAfter == null
                    ? null : Instant.parse(status.failureWindow.nextAttemptAfter);
            if (notBefore != null && now.isBefore(notBefore)) {
                reschedule = min(reschedule, Duration.between(now, notBefore));
            } else {
                int restarted = restartStoppedStatics(task, services, missing, effective);
                created += restarted;
                missing -= restarted;
                for (String newName : ServiceSelection.nextNames(name, allNames, Math.max(0, missing))) {
                    create(task, newName, effective);
                    allNames.add(newName);
                    created++;
                }
            }
        }

        aggregate(task, status, active, created, generation);
        task.setStatus(status);
        return UpdateControl.patchStatus(task).rescheduleAfter(reschedule);
    }

    // ---- Demandes manuelles ----------------------------------------------------------------------

    /** Annotation {@code ksmc.io/start-requests: N} (kubectl) → {@code spec.extraServices += N}, plafonné par max. */
    private void handleStartRequests(ServiceTask task) {
        String raw = task.getMetadata().getAnnotations() == null ? null
                : task.getMetadata().getAnnotations().get(Ksmc.ANNOTATION_START_REQUESTS);
        if (raw == null) {
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            requested = 0;
        }
        ServiceTaskSpec spec = task.getSpec();
        int allowed = spec.maxServiceCount < 0 ? requested
                : Math.max(0, Math.min(requested, spec.maxServiceCount - spec.minServiceCount - spec.extraServices));
        if (allowed < requested) {
            events.warning(task, "MaxServiceCountReached", "requested " + requested + ", started " + allowed);
        }
        int extra = spec.extraServices + allowed;
        client.resources(ServiceTask.class).inNamespace(task.getMetadata().getNamespace())
                .withName(task.getMetadata().getName()).edit(t -> {
                    t.getMetadata().getAnnotations().remove(Ksmc.ANNOTATION_START_REQUESTS);
                    t.getSpec().extraServices = extra;
                    return t;
                });
        spec.extraServices = extra;
    }

    // ---- Création / arrêt ------------------------------------------------------------------------

    private void create(ServiceTask task, String serviceName, EffectiveConfig effective) {
        CloudService svc = new CloudService();
        ObjectMeta meta = new ObjectMetaBuilder().withName(serviceName).withNamespace(task.getMetadata().getNamespace())
                .addToLabels(Ksmc.LABEL_TASK, task.getMetadata().getName())
                .addToLabels(Ksmc.LABEL_ENVIRONMENT, task.getSpec().environment == Environment.PROXY ? "proxy" : "server")
                .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY)
                .addToAnnotations(Ksmc.ANNOTATION_CONFIG_HASH, effective.hash())
                .withOwnerReferences(CloudServiceReconciler.ownerReference(task))
                .build();
        svc.setMetadata(meta);
        svc.setSpec(copy(effective.serviceSpec()));
        try {
            client.resource(svc).create();
            events.normal(task, "ServiceCreated", serviceName);
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) {
                throw e;
            }
        }
    }

    /** Redémarre d'abord les services statiques arrêtés (plus petits numéros) : ils gardent nom et données. */
    private int restartStoppedStatics(ServiceTask task, List<CloudService> services, int missing, EffectiveConfig effective) {
        if (!task.getSpec().staticServices) {
            return 0;
        }
        String name = task.getMetadata().getName();
        List<CloudService> stopped = services.stream()
                .filter(s -> s.getMetadata().getDeletionTimestamp() == null)
                .filter(s -> s.getSpec().desiredState == CloudServiceSpec.DesiredState.Stopped)
                .sorted(Comparator.comparingInt(s -> ServiceSelection.number(name, s.getMetadata().getName())))
                .limit(missing).toList();
        for (CloudService svc : stopped) {
            CloudServiceSpec newSpec = copy(effective.serviceSpec());
            client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                    .withName(svc.getMetadata().getName()).edit(s -> {
                        s.setSpec(newSpec);
                        s.getMetadata().getAnnotations().put(Ksmc.ANNOTATION_CONFIG_HASH, effective.hash());
                        return s;
                    });
            events.normal(task, "ServiceRestarted", svc.getMetadata().getName());
        }
        return stopped.size();
    }

    /** Arrêt : un service dynamique est supprimé ; un statique est arrêté mais conservé (avec son volume). */
    private void stop(CloudService svc, String reason) {
        if (svc.getSpec().staticService) {
            client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                    .withName(svc.getMetadata().getName()).edit(s -> {
                        s.getSpec().desiredState = CloudServiceSpec.DesiredState.Stopped;
                        return s;
                    });
        } else {
            delete(svc);
        }
        LOG.info("Service {} arrêté ({})", svc.getMetadata().getName(), reason);
    }

    private void delete(CloudService svc) {
        client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                .withName(svc.getMetadata().getName()).delete();
        cloudEvents.publish(Snapshots.service("service.deleted", svc));
    }

    // ---- Mises à jour ---------------------------------------------------------------------------

    private Duration applyUpdateStrategy(ServiceTask task, EffectiveConfig effective, List<CloudService> active,
            ServiceTaskStatus status, long generation, Instant now) {
        ServiceTaskSpec.UpdateStrategy strategy = task.getSpec().updateStrategy == null
                ? new ServiceTaskSpec.UpdateStrategy() : task.getSpec().updateStrategy;
        List<CloudService> outdated = active.stream().filter(s -> !effective.hash().equals(hash(s))).toList();
        if (outdated.isEmpty()) {
            Conditions.set(status, generation, PROGRESSING, true, "UpToDate", null);
            return RESYNC;
        }
        if (strategy.type == ServiceTaskSpec.UpdateStrategy.Type.OnRestart) {
            // Les nouveaux services utilisent la nouvelle configuration ; les existants sont conservés.
            return RESYNC;
        }
        String rolloutStart = status.conditions.stream().filter(c -> PROGRESSING.equals(c.getType())
                && "Rolling".equals(c.getReason())).map(c -> c.getLastTransitionTime()).findFirst().orElse(null);
        if (rolloutStart == null) {
            Conditions.set(status, generation, PROGRESSING, true, "Rolling", outdated.size() + " service(s) to update");
        } else if (now.isAfter(Instant.parse(rolloutStart).plusSeconds(strategy.progressDeadlineSeconds))) {
            Conditions.set(status, generation, PROGRESSING, false, "ProgressDeadlineExceeded",
                    "update not finished after " + strategy.progressDeadlineSeconds + "s");
        }
        List<CloudService> toReplace;
        if (strategy.type == ServiceTaskSpec.UpdateStrategy.Type.Recreate) {
            toReplace = outdated;
        } else {
            long unavailable = active.stream().filter(s -> s.getStatus() == null
                    || s.getStatus().phase != Phase.Running).count();
            int budget = (int) Math.max(0, strategy.maxUnavailable - unavailable);
            toReplace = outdated.stream().limit(budget).toList();
        }
        for (CloudService svc : toReplace) {
            replace(task, svc, effective);
            if (!svc.getSpec().staticService) {
                active.remove(svc);
            }
        }
        return Duration.ofSeconds(5);
    }

    /** Remplace un service obsolète : dynamique supprimé (recréé ensuite), statique mis à jour et redémarré. */
    private void replace(ServiceTask task, CloudService svc, EffectiveConfig effective) {
        if (svc.getSpec().staticService) {
            CloudServiceSpec newSpec = copy(effective.serviceSpec());
            client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                    .withName(svc.getMetadata().getName()).edit(s -> {
                        s.setSpec(newSpec);
                        s.getMetadata().getAnnotations().put(Ksmc.ANNOTATION_CONFIG_HASH, effective.hash());
                        return s;
                    });
            client.pods().inNamespace(svc.getMetadata().getNamespace()).withName(svc.getMetadata().getName()).delete();
        } else {
            delete(svc);
        }
        events.normal(task, "ServiceUpdated", svc.getMetadata().getName());
    }

    private static String hash(CloudService svc) {
        return svc.getMetadata().getAnnotations() == null ? null
                : svc.getMetadata().getAnnotations().get(Ksmc.ANNOTATION_CONFIG_HASH);
    }

    // ---- Échecs ----------------------------------------------------------------------------------

    private void recordFailure(ServiceTaskStatus status, CloudService svc, Instant now) {
        ServiceTaskStatus.FailureWindow w = status.failureWindow == null ? new ServiceTaskStatus.FailureWindow()
                : status.failureWindow;
        if (w.windowStart == null || now.isAfter(Instant.parse(w.windowStart).plus(FAILURE_WINDOW))) {
            w.windowStart = now.toString();
            w.failures = 0;
        }
        w.failures = (w.failures == null ? 0 : w.failures) + 1;
        w.lastReason = svc.getMetadata().getName() + ": " + svc.getStatus().reason
                + (svc.getStatus().message == null ? "" : " - " + svc.getStatus().message);
        if (w.failures >= FAILURE_THRESHOLD) {
            Duration delay = CloudServiceReconciler.backoff(w.failures - FAILURE_THRESHOLD + 1);
            w.nextAttemptAfter = now.plus(delay).toString();
        }
        status.failureWindow = w;
    }

    private static void resetFailures(ServiceTaskStatus status, long generation) {
        status.failureWindow = null;
    }

    // ---- Statut ----------------------------------------------------------------------------------

    private void aggregate(ServiceTask task, ServiceTaskStatus status, List<CloudService> active, int created,
            long generation) {
        Map<String, Integer> phases = new LinkedHashMap<>();
        if (created > 0) {
            phases.put(Phase.Pending.name(), created);
        }
        int ready = 0;
        int players = 0;
        for (CloudService svc : active) {
            Phase p = svc.getStatus() == null || svc.getStatus().phase == null ? Phase.Pending : svc.getStatus().phase;
            phases.merge(p.name(), 1, Integer::sum);
            if (p == Phase.Running) {
                ready++;
            }
            if (svc.getStatus() != null && svc.getStatus().onlinePlayers != null) {
                players += svc.getStatus().onlinePlayers;
            }
        }
        status.phases = phases;
        status.readyServices = ready;
        status.totalServices = active.size() + created;
        status.ready = ready + "/" + status.totalServices;
        status.onlinePlayers = players;
        status.selector = Ksmc.LABEL_TASK + "=" + task.getMetadata().getName();
        status.observedGeneration = generation;
        boolean degraded = status.failureWindow != null && status.failureWindow.failures != null
                && status.failureWindow.failures >= FAILURE_THRESHOLD;
        if (degraded && !Conditions.isTrue(status, DEGRADED)) {
            events.warning(task, "Degraded", status.failureWindow.lastReason);
            cloudEvents.publish(Snapshots.resource("task.degraded", task, task.getMetadata().getName()));
        }
        Conditions.set(status, generation, DEGRADED, degraded, degraded ? "CrashLoop" : "Healthy",
                degraded ? status.failureWindow.lastReason : null);
        int min = task.getSpec().enabled ? task.getSpec().minServiceCount : 0;
        Conditions.set(status, generation, Conditions.READY, ready >= min,
                ready >= min ? "MinimumAvailable" : "MinimumUnavailable", ready + " ready, minimum " + min);
    }

    private static CloudServiceSpec copy(CloudServiceSpec spec) {
        return Serialization.unmarshal(Serialization.asJson(spec), CloudServiceSpec.class);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    static Set<String> names(List<CloudService> services) {
        return services.stream().map(s -> s.getMetadata().getName()).collect(Collectors.toSet());
    }
}
