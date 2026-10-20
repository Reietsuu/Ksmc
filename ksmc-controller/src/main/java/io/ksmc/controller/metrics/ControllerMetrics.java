package io.ksmc.controller.metrics;

import io.javaoperatorsdk.operator.api.monitoring.Metrics;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.ServiceTask;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Métriques Prometheus du controller ({@code /metrics}) : état des ressources (services par task et phase, joueurs,
 * tasks dégradées, modules), durée de démarrage des services, réconciliations (via l'interface {@link Metrics} de
 * JOSDK), requêtes API et JVM. L'état des ressources est relu au plus toutes les {@link #REFRESH} lors d'une collecte.
 */
public final class ControllerMetrics implements Metrics {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerMetrics.class);
    static final Duration REFRESH = Duration.ofSeconds(5);

    /** Lecture des ressources du namespace. */
    public interface ClusterView {
        List<CloudService> services();

        List<ServiceTask> tasks();

        List<KsmcModule> modules();
    }

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final ClusterView cluster;
    private final Clock clock;
    private final MultiGauge services;
    private final MultiGauge players;
    private final MultiGauge degraded;
    private final MultiGauge restarts;
    private final MultiGauge modules;
    private Instant refreshedAt = Instant.EPOCH;

    public ControllerMetrics(ClusterView cluster, Clock clock) {
        this.cluster = cluster;
        this.clock = clock;
        services = MultiGauge.builder("ksmc.services").description("Services par task et phase").register(registry);
        players = MultiGauge.builder("ksmc.players").description("Joueurs en ligne par task").register(registry);
        degraded = MultiGauge.builder("ksmc.task.degraded").description("1 si la task est dégradée (crash en boucle)")
                .register(registry);
        restarts = MultiGauge.builder("ksmc.service.restarts").description("Redémarrages du conteneur d'un service")
                .register(registry);
        modules = MultiGauge.builder("ksmc.modules").description("Modules par phase (1 = dans cette phase)")
                .register(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    /** Texte Prometheus, après relecture de l'état des ressources si nécessaire. */
    public synchronized String scrape() {
        Instant now = clock.instant();
        if (Duration.between(refreshedAt, now).compareTo(REFRESH) >= 0) {
            try {
                refresh();
                refreshedAt = now;
            } catch (RuntimeException e) {
                LOG.warn("Métriques : lecture des ressources impossible : {}", e.toString());
            }
        }
        return registry.scrape();
    }

    void refresh() {
        List<CloudService> svcs = cluster.services();
        List<ServiceTask> tasks = cluster.tasks();
        TreeSet<String> taskNames = new TreeSet<>();
        tasks.forEach(t -> taskNames.add(t.getMetadata().getName()));
        Map<String, Map<String, Integer>> byPhase = new LinkedHashMap<>();
        Map<String, Integer> playersByTask = new LinkedHashMap<>();
        List<MultiGauge.Row<?>> restartRows = new ArrayList<>();
        for (CloudService s : svcs) {
            String task = s.getSpec() == null || s.getSpec().task == null ? "" : s.getSpec().task;
            taskNames.add(task);
            CloudServiceStatus st = s.getStatus();
            String phase = st == null || st.phase == null ? CloudServiceStatus.Phase.Pending.name() : st.phase.name();
            byPhase.computeIfAbsent(task, k -> new LinkedHashMap<>()).merge(phase, 1, Integer::sum);
            int online = st == null || st.onlinePlayers == null ? 0 : st.onlinePlayers;
            playersByTask.merge(task, online, Integer::sum);
            int count = st == null || st.restartCount == null ? 0 : st.restartCount;
            restartRows.add(MultiGauge.Row.of(Tags.of("task", task, "service", s.getMetadata().getName()), count));
        }
        List<MultiGauge.Row<?>> serviceRows = new ArrayList<>();
        List<MultiGauge.Row<?>> playerRows = new ArrayList<>();
        for (String task : taskNames) {
            Map<String, Integer> phases = byPhase.getOrDefault(task, Map.of());
            for (CloudServiceStatus.Phase phase : CloudServiceStatus.Phase.values()) {
                serviceRows.add(MultiGauge.Row.of(Tags.of("task", task, "phase", phase.name()),
                        phases.getOrDefault(phase.name(), 0)));
            }
            playerRows.add(MultiGauge.Row.of(Tags.of("task", task), playersByTask.getOrDefault(task, 0)));
        }
        List<MultiGauge.Row<?>> degradedRows = new ArrayList<>();
        for (ServiceTask t : tasks) {
            boolean isDegraded = t.getStatus() != null && t.getStatus().conditions != null && t.getStatus().conditions
                    .stream().anyMatch(c -> "Degraded".equals(c.getType()) && "True".equals(c.getStatus()));
            degradedRows.add(MultiGauge.Row.of(Tags.of("task", t.getMetadata().getName()), isDegraded ? 1 : 0));
        }
        List<MultiGauge.Row<?>> moduleRows = new ArrayList<>();
        for (KsmcModule m : cluster.modules()) {
            String phase = m.getStatus() == null || m.getStatus().phase == null ? "Pending" : m.getStatus().phase.name();
            moduleRows.add(MultiGauge.Row.of(Tags.of("module", m.getMetadata().getName(), "phase", phase), 1));
        }
        services.register(serviceRows, true);
        players.register(playerRows, true);
        degraded.register(degradedRows, true);
        restarts.register(restartRows, true);
        modules.register(moduleRows, true);
    }

    /** Service passé en Running : durée depuis le démarrage du conteneur. */
    public void serviceStarted(String task, Duration duration) {
        Timer.builder("ksmc.service.start").description("Durée de démarrage des services (jusqu'à Running)")
                .tag("task", Objects.requireNonNullElse(task, "")).publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1)).maximumExpectedValue(Duration.ofMinutes(15))
                .register(registry).record(duration);
    }

    /** Requête HTTP traitée ({@code route} = chemin déclaré, pas le chemin concret). */
    public void apiRequest(String method, String route, int status, float millis) {
        Timer.builder("ksmc.api.requests").description("Requêtes de l'API REST")
                .tags("method", method, "route", route, "status", String.valueOf(status))
                .register(registry).record((long) (millis * 1000), TimeUnit.MICROSECONDS);
    }

    @Override
    public <T> T timeControllerExecution(ControllerExecution<T> execution) throws Exception {
        long start = System.nanoTime();
        String result = "success";
        try {
            return execution.execute();
        } catch (Exception e) {
            result = "error";
            throw e;
        } finally {
            Timer.builder("ksmc.reconciliations").description("Réconciliations par controller et résultat")
                    .tags("controller", execution.controllerName(), "operation", execution.name(), "result", result)
                    .register(registry).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** Retire des métriques (modules désactivés). */
    public void remove(Meter meter) {
        registry.remove(meter);
    }
}
