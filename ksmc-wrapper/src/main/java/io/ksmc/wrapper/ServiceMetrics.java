package io.ksmc.wrapper;

import io.ksmc.wire.bridge.ServiceReport;
import java.util.Locale;

/**
 * Métriques Prometheus du service ({@code /metrics} du port de santé), écrites à la main (wrapper Java 8 sans
 * dépendance) : état du process, uptime, et — si le bridge est installé — joueurs, TPS, mémoire et GC de la JVM du
 * serveur d'après le dernier rapport du bridge.
 */
final class ServiceMetrics {
    /** Au-delà, le rapport du bridge est jugé périmé et ses valeurs ne sont plus exposées. */
    static final long STALE_MILLIS = 60000;

    private final String labels;
    private final long startedAt;
    private volatile ServiceReport last;
    private volatile long lastAt;

    ServiceMetrics(String namespace, String service, String task, long startedAt) {
        this.labels = "{namespace=\"" + escape(namespace) + "\",service=\"" + escape(service) + "\",task=\""
                + escape(task) + "\"}";
        this.startedAt = startedAt;
    }

    void update(ServiceReport report, long now) {
        last = report;
        lastAt = now;
    }

    String render(boolean alive, long now) {
        StringBuilder out = new StringBuilder();
        gauge(out, "ksmc_service_up", "1 si le process du serveur tourne", alive ? 1 : 0);
        gauge(out, "ksmc_service_uptime_seconds", "Temps depuis le lancement du serveur",
                (now - startedAt) / 1000.0);
        ServiceReport r = last;
        boolean fresh = r != null && now - lastAt <= STALE_MILLIS;
        gauge(out, "ksmc_service_bridge_connected", "1 si un rapport récent du bridge est disponible", fresh ? 1 : 0);
        if (!fresh) {
            return out.toString();
        }
        gauge(out, "ksmc_service_players", "Joueurs connectés", r.onlinePlayers);
        gauge(out, "ksmc_service_max_players", "Joueurs maximum", r.maxPlayers);
        if (r.tps != null) {
            gauge(out, "ksmc_service_tps", "Ticks par seconde du serveur", r.tps);
        }
        if (r.heapUsedBytes != null) {
            gauge(out, "ksmc_service_jvm_heap_used_bytes", "Tas JVM utilisé", r.heapUsedBytes);
        }
        if (r.heapMaxBytes != null) {
            gauge(out, "ksmc_service_jvm_heap_max_bytes", "Tas JVM maximal", r.heapMaxBytes);
        }
        if (r.gcCount != null) {
            counter(out, "ksmc_service_jvm_gc_collections_total", "Collectes GC", r.gcCount);
        }
        if (r.gcTimeMillis != null) {
            counter(out, "ksmc_service_jvm_gc_seconds_total", "Temps passé en GC", r.gcTimeMillis / 1000.0);
        }
        return out.toString();
    }

    private void gauge(StringBuilder out, String name, String help, double value) {
        metric(out, name, help, "gauge", value);
    }

    private void counter(StringBuilder out, String name, String help, double value) {
        metric(out, name, help, "counter", value);
    }

    private void metric(StringBuilder out, String name, String help, String type, double value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append(labels).append(' ').append(format(value)).append('\n');
    }

    static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
