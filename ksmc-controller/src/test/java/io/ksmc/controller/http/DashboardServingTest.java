package io.ksmc.controller.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.controller.health.HealthRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Le dashboard (construit par :dashboard:npmBuild) est servi sur / avec repli sur index.html hors API. */
class DashboardServingTest {
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeEach
    void start() {
        // Avec métriques : le journal des requêtes ne doit pas gêner les fichiers statiques.
        server = new HttpServer(new HealthRegistry(), java.util.List.of(), new io.ksmc.controller.metrics.ControllerMetrics(
                new io.ksmc.controller.metrics.ControllerMetrics.ClusterView() {
                    public java.util.List<io.ksmc.api.model.CloudService> services() {
                        return java.util.List.of();
                    }

                    public java.util.List<io.ksmc.api.model.ServiceTask> tasks() {
                        return java.util.List.of();
                    }

                    public java.util.List<io.ksmc.api.model.KsmcModule> modules() {
                        return java.util.List.of();
                    }
                }, java.time.Clock.systemUTC())).start(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .header("Accept", accept).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesIndexAndAssets() throws Exception {
        HttpResponse<String> index = get("/", "text/html");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("<div id=\"root\">");
        Matcher script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(index.body());
        assertThat(script.find()).isTrue();
        HttpResponse<String> asset = get(script.group(1), "*/*");
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.headers().firstValue("Content-Type").orElse("")).contains("javascript");
    }

    @Test
    void clientRoutesFallBackToIndex() throws Exception {
        HttpResponse<String> r = get("/services/lobby-1", "text/html");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("<div id=\"root\">");
    }

    @Test
    void unknownApiPathStaysJson404() throws Exception {
        for (String accept : new String[] {"application/json", "text/html"}) {
            HttpResponse<String> r = get("/api/v1/nexistepas", accept);
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.body()).doesNotContain("<div id=\"root\">");
        }
        assertThat(get("/modules/absent/ui.js", "*/*").statusCode()).isEqualTo(404);
    }
}
