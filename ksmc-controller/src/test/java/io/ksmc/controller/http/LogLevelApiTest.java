package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@EnableKubernetesMockClient(crud = true)
class LogLevelApiTest {
    KubernetesClient client;
    ApiTestServer api;
    AuditLog audit;

    @BeforeEach
    void setUp() {
        audit = new AuditLog(null, "test", Clock.systemUTC(), List::of);
        api = new ApiTestServer(client, List.of(new LogLevelApi(audit)), audit);
    }

    @AfterEach
    void tearDown() {
        api.close();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("io.ksmc.test").setLevel(null);
    }

    @Test
    void adminChangesLevelAtRuntime() {
        org.slf4j.Logger log = LoggerFactory.getLogger("io.ksmc.test.Thing");
        assertThat(log.isDebugEnabled()).isFalse();

        HttpResponse<String> r = api.call("PUT", "/api/v1/admin/loglevel", api.admin,
                Map.of("logger", "io.ksmc.test", "level", "debug"), Map.of());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("effective").asText()).isEqualTo("DEBUG");
        assertThat(log.isDebugEnabled()).isTrue();
        assertThat(json(api.call("GET", "/api/v1/admin/loglevel", api.admin, null, Map.of())).get("io.ksmc.test").asText())
                .isEqualTo("DEBUG");
        assertThat(audit.query(null, "admin.loglevel", null, 10)).hasSize(1);

        Map<String, Object> inherit = new java.util.HashMap<>();
        inherit.put("logger", "io.ksmc.test");
        inherit.put("level", null);
        assertThat(api.call("PUT", "/api/v1/admin/loglevel", api.admin, inherit, Map.of()).statusCode()).isEqualTo(200);
        assertThat(log.isDebugEnabled()).isFalse();
    }

    @Test
    void rejectsUnknownLevelAndNonAdmins() {
        assertThat(api.call("PUT", "/api/v1/admin/loglevel", api.admin, Map.of("level", "LOUD"), Map.of()).statusCode())
                .isEqualTo(400);
        assertThat(api.call("PUT", "/api/v1/admin/loglevel", api.operator, Map.of("level", "DEBUG"), Map.of())
                .statusCode()).isEqualTo(403);
        assertThat(api.call("GET", "/api/v1/admin/loglevel", api.viewer, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("ROOT").getLevel())
                .isNotEqualTo(Level.TRACE);
    }

    /** logback.xml du controller, configuré dans un contexte isolé avec le format demandé. */
    static String logWith(String format) throws Exception {
        PrintStream stdout = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("KSMC_LOG_FORMAT", format);
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            JoranConfigurator c = new JoranConfigurator();
            c.setContext(ctx);
            c.doConfigure(LogLevelApiTest.class.getResource("/logback.xml"));
            MDC.put("resource.kind", "ServiceTask");
            MDC.put("resource.name", "lobby");
            ctx.getLogger("io.ksmc.Reconciler").info("réconciliation terminée");
            MDC.clear();
            ctx.getLogger("io.ksmc.Other").info("sans ressource");
        } finally {
            ctx.stop();
            System.setOut(stdout);
            System.clearProperty("KSMC_LOG_FORMAT");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void textAndJsonFormatsIncludeTheResource() throws Exception {
        String text = logWith("TEXT");
        assertThat(text).contains("io.ksmc.Reconciler [ServiceTask/lobby] - réconciliation terminée")
                .contains("io.ksmc.Other - sans ressource");

        String[] lines = logWith("JSON").strip().split("\n");
        assertThat(lines).hasSize(2);
        var first = ApiSupport.JSON.readTree(lines[0]);
        assertThat(first.get("level").asText()).isEqualTo("INFO");
        assertThat(first.get("message").asText()).isEqualTo("réconciliation terminée");
        assertThat(first.get("mdc").get("resource.name").asText()).isEqualTo("lobby");
        assertThat(ApiSupport.JSON.readTree(lines[1]).get("loggerName").asText()).isEqualTo("io.ksmc.Other");
    }
}
