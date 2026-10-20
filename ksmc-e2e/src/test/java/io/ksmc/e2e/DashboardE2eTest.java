package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.EXAMPLES;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleStatus;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTemplate;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Parcours navigateur du dashboard (Playwright, {@code dashboard/e2e}) : connexion, création de task, console,
 * éditeur de template, page du module hello, mobile et thème sombre. Ce test prépare les données, lance Playwright
 * puis vérifie les effets côté cluster. Captures : {@code dashboard/e2e/screenshots}.
 */
@Order(Integer.MAX_VALUE)
class DashboardE2eTest {

    static final Path DASHBOARD = EXAMPLES.getParent().resolve("dashboard");
    static final String TEMPLATE_FILE = "/data/templates/e2ed/tpl/server.properties";
    static ApiClient api;

    @BeforeAll
    static void setUp() throws Exception {
        assertThat(DASHBOARD.resolve("node_modules/@playwright/test")).as("npm ci dans dashboard/").exists();
        api = new ApiClient();
        api.admin();
        ServiceRuntimeE2eTest.create("e2ed-proxy", "environment: proxy\nsoftware: {name: velocity, version: latest}\nmemoryMb: 512");

        Path props = Files.createTempFile("server", ".properties");
        Files.writeString(props, "motd=Ksmc e2e\n");
        ModuleSystemE2eTest.upload(TEMPLATE_FILE, props);
        ModuleSystemE2eTest.apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTemplate
                metadata: {name: e2ed-tpl}
                spec: {storage: {path: e2ed/tpl}}
                """);

        ModuleSystemE2eTest.upload("/data/modules/hello.jar", ModuleSystemE2eTest.MODULE_JAR);
        ModuleSystemE2eTest.hello("Salut", true);
        ModuleSystemE2eTest.awaitPhase("hello", KsmcModuleStatus.Phase.Enabled);
        ServiceRuntimeE2eTest.awaitPhase("e2ed-proxy", Phase.Running, ServiceRuntimeE2eTest.START_TIMEOUT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2ed-web").delete();
        CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName("e2ed-proxy").delete();
        CLIENT.resources(ServiceTemplate.class).inNamespace(NAMESPACE).withName("e2ed-tpl").delete();
        CLIENT.resources(KsmcModule.class).inNamespace(NAMESPACE).withName("hello").delete();
        if (api != null) {
            api.close();
        }
    }

    @Test
    void browserJourneys() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(List.of("npx", "playwright", "test")).directory(DASHBOARD.toFile())
                .inheritIO();
        pb.environment().put("KSMC_URL", api.base());
        pb.environment().put("KSMC_USER", "admin");
        pb.environment().put("KSMC_PASSWORD", ApiClient.ADMIN_PASSWORD);
        pb.environment().put("CI", "true");
        Process p = pb.start();
        assertThat(p.waitFor(20, TimeUnit.MINUTES)).as("Playwright terminé à temps").isTrue();
        assertThat(p.exitValue()).as("parcours Playwright (voir la sortie ci-dessus)").isZero();

        // Effets visibles côté cluster.
        ServiceTask task = CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2ed-web").get();
        assertThat(task).as("task créée depuis le dashboard").isNotNull();
        assertThat(task.getSpec().memoryMb).isEqualTo(1024);
        HttpResponse<String> file = api.call("GET", "/api/v1/templates/e2ed/tpl/files/server.properties", api.admin(), null);
        assertThat(file.body()).contains("motd=Ksmc e2e").contains("max-players=42");
        assertThat(Files.list(DASHBOARD.resolve("e2e/screenshots")).count()).isGreaterThanOrEqualTo(6);
    }
}
