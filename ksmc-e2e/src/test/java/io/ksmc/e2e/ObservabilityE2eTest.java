package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.LocalPortForward;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.ServiceTask;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Observabilité : {@code /metrics} du controller (services par task et phase), {@code /metrics} d'un service Paper
 * avec bridge (joueurs, TPS, JVM) et niveau de log changé à chaud par l'API.
 */
class ObservabilityE2eTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
        api.admin();
        TaskOrchestrationE2eTest.apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2eo-lobby, labels: {e2e: observability}}
                spec:
                  environment: server
                  software: {name: paper, version: latest}
                  memoryMb: 1024
                  minServiceCount: 1
                """);
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2eo-lobby").delete();
        api.close();
    }

    static String get(String url) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "";
        }
    }

    static CloudService runningService() {
        List<CloudService> s = TaskOrchestrationE2eTest.services("e2eo-lobby-");
        return s.stream().filter(x -> x.getStatus() != null && x.getStatus().phase == Phase.Running).findFirst()
                .orElse(null);
    }

    @Test
    void controllerAndServiceMetricsAndRuntimeLogLevel() throws Exception {
        await("service e2eo-lobby en ligne", ServiceRuntimeE2eTest.START_TIMEOUT, () -> runningService() != null);
        CloudService svc = runningService();

        // Controller : la jauge reflète l'état (relue au plus toutes les 5 s).
        await("ksmc_services du controller", Duration.ofSeconds(60), () -> get(api.base() + "/metrics")
                .contains("ksmc_services{phase=\"Running\",task=\"e2eo-lobby\"} 1.0"));
        String controller = get(api.base() + "/metrics");
        assertThat(controller).contains("ksmc_players{task=\"e2eo-lobby\"}")
                .contains("ksmc_reconciliations_seconds_count{controller=")
                .contains("ksmc_api_requests_seconds_count")
                .contains("ksmc_service_start_seconds_count{task=\"e2eo-lobby\"}")
                .contains("jvm_memory_used_bytes");

        // Service : port de santé 8090 du Pod.
        try (LocalPortForward fwd = CLIENT.pods().inNamespace(NAMESPACE).withName(svc.getStatus().podName)
                .portForward(8090)) {
            String url = "http://localhost:" + fwd.getLocalPort() + "/metrics";
            await("TPS du service rapporté par le bridge", Duration.ofSeconds(90), () -> get(url)
                    .contains("ksmc_service_tps{"));
            String text = get(url);
            String labels = "{namespace=\"" + NAMESPACE + "\",service=\"" + svc.getMetadata().getName()
                    + "\",task=\"e2eo-lobby\"}";
            assertThat(text).contains("ksmc_service_up" + labels + " 1")
                    .contains("ksmc_service_bridge_connected" + labels + " 1")
                    .contains("ksmc_service_players" + labels + " 0")
                    .contains("ksmc_service_jvm_heap_used_bytes" + labels)
                    .contains("ksmc_service_jvm_gc_collections_total" + labels);
        }

        // Logs : DEBUG activé à chaud pour JOSDK, visible dans les logs du controller sans redémarrage.
        String pod = CLIENT.pods().inNamespace(NAMESPACE).withLabel("app.kubernetes.io/component", "controller")
                .list().getItems().getFirst().getMetadata().getName();
        Instant since = Instant.now();
        HttpResponse<String> r = api.call("PUT", "/api/v1/admin/loglevel", api.admin(),
                Map.of("logger", "io.javaoperatorsdk", "level", "DEBUG"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        try {
            // Provoque des réconciliations.
            CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2eo-lobby")
                    .edit(t -> {
                        t.getMetadata().getLabels().put("e2e-touch", String.valueOf(System.nanoTime()));
                        return t;
                    });
            await("logs DEBUG du controller", Duration.ofSeconds(60), () -> CLIENT.pods().inNamespace(NAMESPACE)
                    .withName(pod).inContainer("controller").sinceTime(since.toString()).getLog().contains(" DEBUG "));
        } finally {
            api.call("PUT", "/api/v1/admin/loglevel", api.admin(), Map.of("logger", "io.javaoperatorsdk", "level", "INFO"));
        }
    }
}
