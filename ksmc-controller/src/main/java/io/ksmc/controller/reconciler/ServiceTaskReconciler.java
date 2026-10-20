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
import io.ksmc.controller.bridge.BridgeHub;
import io.ksmc.controller.messaging.CloudEvents;
import io.ksmc.controller.messaging.Snapshots;
import io.ksmc.controller.task.Autoscaler;
import io.ksmc.controller.task.EffectiveConfig;
import io.ksmc.controller.task.PlayersScalingStrategy;
import io.ksmc.controller.task.ProxyExposure;
import io.ksmc.controller.task.ScalingStrategies;
import io.ksmc.controller.task.ScheduleScalingStrategy;
import io.ksmc.controller.task.ServiceSelection;
import io.ksmc.wire.messaging.CloudEvent;
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
    /** Tasks avec autoscaling : réévaluation régulière (joueurs, horaires). */
    static final Duration AUTOSCALING_RESYNC = Duration.ofSeconds(10);

    private final KubernetesClient client;
    private final ScalingStrategies strategies;
    private final EventRecorder events;
    private final CloudEvents cloudEvents;
    private final BridgeHub bridges;
    private final Clock clock;

    public ServiceTaskReconciler(KubernetesClient client, ScalingStrategies strategies, EventRecorder events,
            CloudEvents cloudEvents, BridgeHub bridges, Clock clock) {
        this.client = client;
        this.strategies = strategies;
        this.events = events;
        this.cloudEvents = cloudEvents;
        this.bridges = bridges;
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

        if (ProxyExposure.applies(task)) {
            exposeProxy(task);
        }

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
        task.setStatus(status);
        boolean autoscaled = spec.enabled && Autoscaler.enabled(task);
        List<CloudService> prepared = new ArrayList<>(active.stream().filter(Autoscaler::prepared).toList());
        List<CloudService> routable = new ArrayList<>(active.stream().filter(s -> !Autoscaler.prepared(s)).toList());
        if (!autoscaled) {
            // Plus d'autoscaling : les services préparés restants deviennent des services normaux.
            prepared.forEach(this::promote);
            routable.addAll(prepared);
            prepared.clear();
        }
        ServiceTaskStatus.AutoscalingState scaling = null;
        if (autoscaled) {
            scaling = status.autoscaling == null ? new ServiceTaskStatus.AutoscalingState() : status.autoscaling;
            Autoscaler.observe(scaling, active, now);
            status.autoscaling = scaling;
        } else {
            status.autoscaling = null;
        }
        int target = spec.enabled ? strategies.target(new ScalingStrategy.Context(task, active, now)) : 0;
        if (autoscaled) {
            target = decide(task, scaling, routable, target, now);
        }

        // Mise à jour de configuration des services existants.
        reschedule = min(reschedule, applyUpdateStrategy(task, effective, active, status, generation, now));
        // Les services remplacés par la mise à jour ne comptent plus.
        routable.retainAll(active);
        prepared.retainAll(active);

        // Réduction (les services vides depuis le plus longtemps d'abord).
        if (routable.size() > target) {
            Map<String, String> emptySince = scaling == null ? Map.of() : scaling.emptySince;
            for (CloudService svc : ServiceSelection.toStop(name, routable, routable.size() - target, emptySince)) {
                stop(svc, spec.enabled ? "ScaledDown" : "TaskDisabled");
                active.remove(svc);
                routable.remove(svc);
            }
        }
        // Augmentation : promotion des services préparés (Running d'abord), puis création (avec backoff si la task
        // échoue en boucle), puis renouvellement des services préparés.
        int missing = target - routable.size();
        prepared.sort(Comparator.comparing((CloudService s) -> ServiceSelection.isRunning(s) ? 0 : 1));
        while (missing > 0 && !prepared.isEmpty()) {
            CloudService promoted = prepared.removeFirst();
            promote(promoted);
            events.normal(task, "ServicePromoted", promoted.getMetadata().getName());
            routable.add(promoted);
            missing--;
        }
        int preparedMissing = autoscaled ? Autoscaler.preparedWanted(task) - prepared.size() : 0;
        if (preparedMissing < 0) {
            for (CloudService svc : ServiceSelection.toStop(name, prepared, -preparedMissing)) {
                stop(svc, "PreparedScaledDown");
                active.remove(svc);
            }
        }
        int created = 0;
        if (missing > 0 || preparedMissing > 0) {
            Instant notBefore = status.failureWindow == null || status.failureWindow.nextAttemptAfter == null
                    ? null : Instant.parse(status.failureWindow.nextAttemptAfter);
            if (notBefore != null && now.isBefore(notBefore)) {
                reschedule = min(reschedule, Duration.between(now, notBefore));
            } else {
                if (missing > 0) {
                    int restarted = restartStoppedStatics(task, services, missing, effective);
                    created += restarted;
                    missing -= restarted;
                }
                List<String> names = ServiceSelection.nextNames(name, allNames,
                        Math.max(0, missing) + Math.max(0, preparedMissing));
                for (int i = 0; i < names.size(); i++) {
                    create(task, names.get(i), effective, i >= Math.max(0, missing));
                    allNames.add(names.get(i));
                    created++;
                }
            }
        }
        if (autoscaled) {
            scaling.preparedServices = Autoscaler.preparedWanted(task);
            reschedule = min(reschedule, AUTOSCALING_RESYNC);
        }

        aggregate(task, status, active, created, generation);
        task.setStatus(status);
        return UpdateControl.patchStatus(task).rescheduleAfter(reschedule);
    }

    /** Crée ou met à jour le Service Kubernetes d'une task proxy (server-side apply, possédé par la task). */
    private void exposeProxy(ServiceTask task) {
        var service = ProxyExposure.build(task);
        service.getMetadata().setOwnerReferences(List.of(CloudServiceReconciler.ownerReference(task)));
        try {
            client.services().inNamespace(task.getMetadata().getNamespace()).resource(service)
                    .fieldManager("ksmc-controller").forceConflicts().serverSideApply();
        } catch (KubernetesClientException e) {
            events.warning(task, "ExposeFailed", e.getMessage());
        }
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

    // ---- Autoscaling ----------------------------------------------------------------------------

    /** Applique cooldown et démarrages simultanés à la cible proposée ; trace les décisions. */
    private int decide(ServiceTask task, ServiceTaskStatus.AutoscalingState scaling, List<CloudService> routable,
            int proposed, Instant now) {
        ServiceTaskSpec spec = task.getSpec();
        int current = routable.size();
        int starting = (int) routable.stream().filter(s -> !ServiceSelection.isRunning(s)).count();
        int scheduled = strategies.proposal(ScheduleScalingStrategy.NAME, new ScalingStrategy.Context(task, routable, now));
        int baseline = ScalingStrategies.bound(spec, Math.max(spec.minServiceCount + spec.extraServices, scheduled));
        Double fill = PlayersScalingStrategy.fill(routable);
        int idle = PlayersScalingStrategy.idle(task, routable, now).size();
        String reason;
        if (proposed > current) {
            reason = fill != null && fill > PlayersScalingStrategy.threshold(task)
                    ? "remplissage %d %% > seuil %d %%".formatted(Math.round(fill * 100),
                            Math.round(PlayersScalingStrategy.threshold(task) * 100))
                    : scheduled >= proposed ? "minimum programmé de %d service(s)".formatted(scheduled)
                    : "minimum de %d service(s)".formatted(proposed);
        } else {
            reason = idle > 0 ? "%d service(s) vide(s) depuis %d s".formatted(idle, PlayersScalingStrategy.idleSeconds(task))
                    : "cible réduite à %d".formatted(proposed);
        }
        Autoscaler.Decision d = Autoscaler.decide(task, current, starting, proposed, baseline, now, reason);
        scaling.observedFill = fill == null ? null : Math.round(fill * 1000) / 1000.0;
        scaling.desiredServices = d.target();
        if (d.direction() != null) {
            scaling.lastScaleTime = now.toString();
            scaling.lastDecision = d.direction();
            scaling.lastReason = d.reason();
            String message = "%d → %d : %s".formatted(current, d.target(), d.reason());
            events.normal(task, d.direction(), message);
            CloudEvent event = Snapshots.resource("task.scaled", task, task.getMetadata().getName());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("from", current);
            data.put("to", d.target());
            data.put("direction", d.direction());
            data.put("reason", d.reason());
            data.put("fill", scaling.observedFill);
            event.data = Snapshots.json(data);
            cloudEvents.publish(event);
            LOG.info("Task {} : {} ({})", task.getMetadata().getName(), d.direction(), message);
        }
        return d.target();
    }

    /** Rend un service préparé routable (retrait du label) et met à jour le registre des proxies sans attendre. */
    private void promote(CloudService svc) {
        CloudService updated = client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                .withName(svc.getMetadata().getName()).edit(s -> {
                    s.getMetadata().getLabels().remove(Ksmc.LABEL_PREPARED);
                    return s;
                });
        svc.getMetadata().getLabels().remove(Ksmc.LABEL_PREPARED);
        bridges.sync(updated);
    }

    // ---- Création / arrêt ------------------------------------------------------------------------

    private void create(ServiceTask task, String serviceName, EffectiveConfig effective, boolean prepared) {
        CloudService svc = new CloudService();
        ObjectMeta meta = new ObjectMetaBuilder().withName(serviceName).withNamespace(task.getMetadata().getNamespace())
                .addToLabels(Ksmc.LABEL_TASK, task.getMetadata().getName())
                .addToLabels(Ksmc.LABEL_ENVIRONMENT, task.getSpec().environment == Environment.PROXY ? "proxy" : "server")
                .addToLabels(Ksmc.LABEL_MANAGED_BY, Ksmc.MANAGED_BY)
                .addToAnnotations(Ksmc.ANNOTATION_CONFIG_HASH, effective.hash())
                .withOwnerReferences(CloudServiceReconciler.ownerReference(task))
                .build();
        if (prepared) {
            meta.getLabels().put(Ksmc.LABEL_PREPARED, "true");
        }
        svc.setMetadata(meta);
        svc.setSpec(copy(effective.serviceSpec()));
        try {
            client.resource(svc).create();
            events.normal(task, prepared ? "PreparedServiceCreated" : "ServiceCreated", serviceName);
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
        bridges.remove(svc.getMetadata().getName());
        cloudEvents.publish(Snapshots.service("service.deleted", svc));
    }

    // ---- Mises à jour ---------------------------------------------------------------------------

    private Duration applyUpdateStrategy(ServiceTask task, EffectiveConfig effective, List<CloudService> active,
            ServiceTaskStatus status, long generation, Instant now) {
        ServiceTaskSpec.UpdateStrategy strategy = task.getSpec().updateStrategy == null
                ? new ServiceTaskSpec.UpdateStrategy() : task.getSpec().updateStrategy;
        applyHotProperties(effective, active);
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

    /** Propage les réglages à chaud (maintenance) aux services existants, sans les redémarrer. */
    private void applyHotProperties(EffectiveConfig effective, List<CloudService> active) {
        for (CloudService svc : active) {
            Map<String, String> wanted = new LinkedHashMap<>();
            for (String key : EffectiveConfig.HOT_PROPERTIES) {
                String target = effective.serviceSpec().properties.get(key);
                String current = svc.getSpec().properties == null ? null : svc.getSpec().properties.get(key);
                if (!java.util.Objects.equals(target, current)) {
                    wanted.put(key, target);
                }
            }
            if (wanted.isEmpty()) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode patch =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            com.fasterxml.jackson.databind.node.ObjectNode props = patch.putObject("spec").putObject("properties");
            wanted.forEach(props::put); // null : propriété retirée (JSON merge patch)
            client.resources(CloudService.class).inNamespace(svc.getMetadata().getNamespace())
                    .withName(svc.getMetadata().getName())
                    .patch(io.fabric8.kubernetes.client.dsl.base.PatchContext.of(
                            io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE), patch.toString());
            wanted.forEach((k, v) -> {
                if (v == null) {
                    svc.getSpec().properties.remove(k);
                } else {
                    svc.getSpec().properties.put(k, v);
                }
            });
        }
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
