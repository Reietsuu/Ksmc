package io.ksmc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.controller.health.HealthCheck;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.http.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthEndpointsTest {
    private final AtomicBoolean natsUp = new AtomicBoolean(true);
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeEach
    void start() {
        HealthRegistry registry = new HealthRegistry();
        registry.register(fixed("kubernetes", () -> true));
        registry.register(fixed("nats", natsUp::get));
        server = new HttpServer(registry).start(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void readyWhenAllChecksPass() throws Exception {
        assertThat(get("/readyz").statusCode()).isEqualTo(200);
    }

    @Test
    void notReadyButAliveWhenNatsDown() throws Exception {
        natsUp.set(false);

        HttpResponse<String> ready = get("/readyz");
        assertThat(ready.statusCode()).isEqualTo(503);
        assertThat(ready.body()).contains("\"nats\"").contains("down");
        assertThat(get("/healthz").statusCode()).isEqualTo(200);
    }

    @Test
    void failingCheckIsReportedNotThrown() throws Exception {
        HealthRegistry registry = new HealthRegistry();
        registry.register(new HealthCheck() {
            public String name() {
                return "broken";
            }

            public Result check() {
                throw new IllegalStateException("boom");
            }
        });
        assertThat(registry.readiness().ready()).isFalse();
        assertThat(registry.readiness().checks().get("broken").detail()).contains("boom");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HealthCheck fixed(String name, java.util.function.BooleanSupplier up) {
        return new HealthCheck() {
            public String name() {
                return name;
            }

            public Result check() {
                return up.getAsBoolean() ? Result.up() : Result.down("down");
            }
        };
    }
}
