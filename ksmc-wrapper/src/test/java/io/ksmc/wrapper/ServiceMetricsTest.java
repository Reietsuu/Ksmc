package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.bridge.ServiceReport;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ServiceMetricsTest {

    static final String LABELS = "{namespace=\"prod\",service=\"lobby-1\",task=\"lobby\"}";

    static ServiceReport report() {
        ServiceReport r = new ServiceReport();
        r.onlinePlayers = 3;
        r.maxPlayers = 20;
        r.tps = 19.87;
        r.heapUsedBytes = 512L * 1024 * 1024;
        r.heapMaxBytes = 1024L * 1024 * 1024;
        r.gcCount = 12L;
        r.gcTimeMillis = 1500L;
        return r;
    }

    @Test
    void withoutBridgeOnlyProcessMetrics() {
        ServiceMetrics m = new ServiceMetrics("prod", "lobby-1", "lobby", 1000);
        String text = m.render(true, 61_000);
        assertThat(text).contains("ksmc_service_up" + LABELS + " 1\n")
                .contains("ksmc_service_uptime_seconds" + LABELS + " 60\n")
                .contains("ksmc_service_bridge_connected" + LABELS + " 0\n")
                .contains("# TYPE ksmc_service_up gauge")
                .doesNotContain("ksmc_service_tps");
    }

    @Test
    void bridgeReportGivesPlayersTpsAndJvm() {
        ServiceMetrics m = new ServiceMetrics("prod", "lobby-1", "lobby", 0);
        m.update(report(), 10_000);
        String text = m.render(true, 12_000);
        assertThat(text).contains("ksmc_service_players" + LABELS + " 3\n")
                .contains("ksmc_service_max_players" + LABELS + " 20\n")
                .contains("ksmc_service_tps" + LABELS + " 19.870\n")
                .contains("ksmc_service_jvm_heap_used_bytes" + LABELS + " 536870912\n")
                .contains("ksmc_service_jvm_heap_max_bytes" + LABELS + " 1073741824\n")
                .contains("ksmc_service_jvm_gc_collections_total" + LABELS + " 12\n")
                .contains("# TYPE ksmc_service_jvm_gc_seconds_total counter")
                .contains("ksmc_service_jvm_gc_seconds_total" + LABELS + " 1.500\n");
        // Rapport périmé (bridge arrêté) : valeurs retirées.
        assertThat(m.render(true, 10_000 + ServiceMetrics.STALE_MILLIS + 1)).doesNotContain("ksmc_service_tps")
                .contains("ksmc_service_bridge_connected" + LABELS + " 0");
    }

    @Test
    void labelsAreEscaped() {
        assertThat(ServiceMetrics.escape("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
        assertThat(new ServiceMetrics("p", "s", null, 0).render(false, 0)).contains("task=\"\"").contains("ksmc_service_up{namespace=\"p\",service=\"s\",task=\"\"} 0");
    }

    @Test
    void servedByHealthServer() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ServiceMetrics m = new ServiceMetrics("prod", "lobby-1", "lobby", System.currentTimeMillis());
        m.update(report(), System.currentTimeMillis());
        HealthServer h = new HealthServer(port, () -> true, 1, m);
        h.start();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + port + "/metrics").openConnection();
            assertThat(c.getResponseCode()).isEqualTo(200);
            assertThat(c.getContentType()).startsWith("text/plain; version=0.0.4");
            try (InputStream in = c.getInputStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains("ksmc_service_tps" + LABELS);
            }
        } finally {
            h.stop();
        }
    }
}
