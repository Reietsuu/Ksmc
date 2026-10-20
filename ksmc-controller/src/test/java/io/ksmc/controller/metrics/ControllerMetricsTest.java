package io.ksmc.controller.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.javaoperatorsdk.operator.api.monitoring.Metrics;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleStatus;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskStatus;
import io.ksmc.api.module.ModuleMetrics;
import io.ksmc.api.module.Registry;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.http.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControllerMetricsTest {

    final List<CloudService> services = new ArrayList<>();
    final List<ServiceTask> tasks = new ArrayList<>();
    final List<KsmcModule> modules = new ArrayList<>();
    final AtomicInteger listed = new AtomicInteger();
    final ControllerMetrics metrics = new ControllerMetrics(new ControllerMetrics.ClusterView() {
        public List<CloudService> services() {
            listed.incrementAndGet();
            return services;
        }

        public List<ServiceTask> tasks() {
            return tasks;
        }

        public List<KsmcModule> modules() {
            return modules;
        }
    }, Clock.systemUTC());

    static CloudService service(String name, String task, CloudServiceStatus.Phase phase, int players, int restarts) {
        CloudService s = new CloudService();
        s.setMetadata(new ObjectMetaBuilder().withName(name).build());
        s.setSpec(new CloudServiceSpec());
        s.getSpec().task = task;
        CloudServiceStatus st = new CloudServiceStatus();
        st.phase = phase;
        st.onlinePlayers = players;
        st.restartCount = restarts;
        s.setStatus(st);
        return s;
    }

    static ServiceTask task(String name, boolean degraded) {
        ServiceTask t = new ServiceTask();
        t.setMetadata(new ObjectMetaBuilder().withName(name).build());
        t.setStatus(new ServiceTaskStatus());
        if (degraded) {
            Condition c = new Condition();
            c.setType("Degraded");
            c.setStatus("True");
            t.getStatus().conditions.add(c);
        }
        return t;
    }

    @Test
    void exposesServicesPlayersDegradedAndModules() {
        tasks.add(task("lobby", false));
        tasks.add(task("bedwars", true));
        services.add(service("lobby-1", "lobby", CloudServiceStatus.Phase.Running, 3, 0));
        services.add(service("lobby-2", "lobby", CloudServiceStatus.Phase.Running, 4, 2));
        services.add(service("lobby-3", "lobby", CloudServiceStatus.Phase.Starting, 0, 0));
        KsmcModule hello = new KsmcModule();
        hello.setMetadata(new ObjectMetaBuilder().withName("hello").build());
        hello.setStatus(new KsmcModuleStatus());
        hello.getStatus().phase = KsmcModuleStatus.Phase.Enabled;
        modules.add(hello);

        String text = metrics.scrape();

        assertThat(text).contains("ksmc_services{phase=\"Running\",task=\"lobby\"} 2.0")
                .contains("ksmc_services{phase=\"Starting\",task=\"lobby\"} 1.0")
                .contains("ksmc_services{phase=\"Failed\",task=\"lobby\"} 0.0")
                .contains("ksmc_services{phase=\"Running\",task=\"bedwars\"} 0.0")
                .contains("ksmc_players{task=\"lobby\"} 7.0")
                .contains("ksmc_task_degraded{task=\"bedwars\"} 1.0")
                .contains("ksmc_task_degraded{task=\"lobby\"} 0.0")
                .contains("ksmc_service_restarts{service=\"lobby-2\",task=\"lobby\"} 2.0")
                .contains("ksmc_modules{module=\"hello\",phase=\"Enabled\"} 1.0")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void removedServicesDisappearAndRefreshIsThrottled() {
        services.add(service("lobby-1", "lobby", CloudServiceStatus.Phase.Running, 1, 0));
        assertThat(metrics.scrape()).contains("ksmc_service_restarts{service=\"lobby-1\"");
        services.clear();
        metrics.scrape();
        assertThat(listed).hasValue(1); // relecture limitée à une fois par REFRESH
        metrics.refresh();
        assertThat(metrics.registry().scrape()).doesNotContain("service=\"lobby-1\"");
    }

    @Test
    void recordsStartDurationsAndReconciliations() throws Exception {
        metrics.serviceStarted("lobby", Duration.ofSeconds(42));
        Integer r = metrics.timeControllerExecution(new Metrics.ControllerExecution<>() {
            public String name() {
                return "reconcile";
            }

            public String controllerName() {
                return "cloudservice";
            }

            public String successTypeName(Integer result) {
                return "ok";
            }

            public io.javaoperatorsdk.operator.processing.event.ResourceID resourceID() {
                return null;
            }

            public java.util.Map<String, Object> metadata() {
                return java.util.Map.of();
            }

            public Integer execute() {
                return 1;
            }
        });
        assertThat(r).isEqualTo(1);
        String text = metrics.registry().scrape();
        assertThat(text).contains("ksmc_service_start_seconds_count{task=\"lobby\"} 1")
                .contains("ksmc_service_start_seconds_sum{task=\"lobby\"} 42.0")
                .contains("ksmc_reconciliations_seconds_count{controller=\"cloudservice\",operation=\"reconcile\",result=\"success\"} 1");
    }

    @Test
    void moduleMetricsArePrefixedAndRemovedOnDisable() {
        List<AutoCloseable> onDisable = new ArrayList<>();
        Registry registry = new Registry() {
            public <T> Registration register(Class<T> spi, T implementation) {
                throw new UnsupportedOperationException();
            }

            public Registration onDisable(AutoCloseable resource) {
                onDisable.add(resource);
                return () -> { };
            }
        };
        ModuleMetrics m = new ModuleMetricsImpl(metrics.registry(), "hello", registry);
        ModuleMetrics.Counter greetings = m.counter("greetings");
        greetings.increment();
        greetings.increment();
        m.gauge("queue", () -> 5, "kind", "test");
        assertThat(metrics.registry().scrape()).contains("ksmc_module_hello_greetings_total 2.0")
                .contains("ksmc_module_hello_queue{kind=\"test\"} 5.0");
        assertThatThrownBy(() -> m.counter("Bad-Name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.counter("ok", "orphan")).isInstanceOf(IllegalArgumentException.class);

        onDisable.forEach(c -> {
            try {
                c.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(metrics.registry().scrape()).doesNotContain("ksmc_module_hello");
    }

    @Test
    void httpEndpointAndApiRequestTiming() throws Exception {
        try (HttpServer server = new HttpServer(new HealthRegistry(),
                List.of(routes -> routes.get("/api/v1/things/{name}", ctx -> ctx.result("ok"))), metrics).start(0)) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/things/a")).build(), HttpResponse.BodyHandlers.ofString());
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/things/b")).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
            assertThat(r.body()).contains(
                    "ksmc_api_requests_seconds_count{method=\"GET\",route=\"/api/v1/things/{name}\",status=\"200\"} 2");
        }
    }
}
