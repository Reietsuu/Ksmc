package io.ksmc.controller.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.spi.AuditSink;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journal d'audit : une ligne JSON par action (logger {@code ksmc.audit}), les {@value #KEEP} dernières entrées dans le
 * ConfigMap {@code ksmc-audit} (écrit au plus toutes les 5 s) et les {@link AuditSink} des modules.
 */
public final class AuditLog implements AutoCloseable {

    static final int KEEP = 1000;
    public static final String CONFIG_MAP = "ksmc-audit";
    private static final Logger AUDIT = LoggerFactory.getLogger("ksmc.audit");
    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesClient client;
    private final String namespace;
    private final Clock clock;
    private final Supplier<List<AuditSink>> sinks;
    private final Deque<AuditSink.Entry> entries = new ArrayDeque<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ksmc-audit");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean dirty;

    /** @param client {@code null} : pas de persistance (tests) */
    public AuditLog(KubernetesClient client, String namespace, Clock clock, Supplier<List<AuditSink>> sinks) {
        this.client = client;
        this.namespace = namespace;
        this.clock = clock;
        this.sinks = sinks;
        if (client != null) {
            load();
            executor.scheduleWithFixedDelay(this::flush, 5, 5, TimeUnit.SECONDS);
        }
    }

    public void record(String user, String action, String target, String result, String detail) {
        AuditSink.Entry e = new AuditSink.Entry(clock.instant().toString(), user, action, target, result, detail);
        synchronized (entries) {
            entries.addLast(e);
            while (entries.size() > KEEP) {
                entries.removeFirst();
            }
        }
        dirty = true;
        try {
            AUDIT.info(JSON.writeValueAsString(e));
        } catch (java.io.IOException ignored) {
            // journalisation best effort
        }
        List<AuditSink> list = sinks.get();
        if (!list.isEmpty()) {
            executor.execute(() -> list.forEach(s -> {
                try {
                    s.record(e);
                } catch (RuntimeException ex) {
                    LOG.warn("AuditSink {} en échec : {}", s.getClass().getName(), ex.toString());
                }
            }));
        }
    }

    /** Entrées les plus récentes d'abord, filtrées (critères {@code null} ignorés). */
    public List<AuditSink.Entry> query(String user, String action, String target, int limit) {
        List<AuditSink.Entry> out = new ArrayList<>();
        synchronized (entries) {
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                AuditSink.Entry e = it.next();
                if ((user == null || user.equals(e.user())) && (action == null || e.action().startsWith(action))
                        && (target == null || target.equals(e.target()))) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private void load() {
        try {
            ConfigMap cm = client.configMaps().inNamespace(namespace).withName(CONFIG_MAP).get();
            if (cm != null && cm.getData() != null && cm.getData().get("entries.json") != null) {
                List<AuditSink.Entry> saved = JSON.readValue(cm.getData().get("entries.json"),
                        new TypeReference<List<AuditSink.Entry>>() { });
                synchronized (entries) {
                    entries.addAll(saved);
                }
            }
        } catch (Exception e) {
            LOG.warn("Audit précédent illisible : {}", e.getMessage());
        }
    }

    void flush() {
        if (!dirty || client == null) {
            return;
        }
        dirty = false;
        try {
            List<AuditSink.Entry> copy;
            synchronized (entries) {
                copy = new ArrayList<>(entries);
            }
            client.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder().withNewMetadata()
                    .withName(CONFIG_MAP).withNamespace(namespace).addToLabels("app.kubernetes.io/managed-by", "ksmc")
                    .endMetadata().addToData("entries.json", JSON.writeValueAsString(copy)).build())
                    .fieldManager("ksmc-audit").forceConflicts().serverSideApply();
        } catch (Exception e) {
            dirty = true;
            LOG.debug("Audit non persisté : {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        flush();
        executor.shutdownNow();
    }
}
