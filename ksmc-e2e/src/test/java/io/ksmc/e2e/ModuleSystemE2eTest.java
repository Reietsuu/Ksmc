package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.EXAMPLES;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleStatus;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTemplate;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Scénarios du change p06 : module hello (store), REST, configuration, checksum, plugin-hello sur Paper. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModuleSystemE2eTest {

    static final Duration TIMEOUT = Duration.ofMinutes(6);
    static final Path MODULE_JAR = EXAMPLES.resolve("module-hello/build/libs/ksmc-module-hello.jar");
    static final Path PLUGIN_JAR = EXAMPLES.resolve("plugin-hello/build/libs/ksmc-plugin-hello.jar");
    static ApiClient api;

    @BeforeAll
    static void setUp() {
        assertThat(MODULE_JAR).as("construire d'abord examples/module-hello").exists();
        assertThat(PLUGIN_JAR).as("construire d'abord examples/plugin-hello").exists();
        api = new ApiClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withLabel("e2e", "module").delete();
        CLIENT.resources(ServiceTemplate.class).inNamespace(NAMESPACE).withLabel("e2e", "module").delete();
        CLIENT.resources(KsmcModule.class).inNamespace(NAMESPACE).withLabel("e2e", "module").delete();
        if (api != null) {
            api.close();
        }
    }

    static void apply(String yaml) {
        CLIENT.resource(Serialization.unmarshal(yaml, GenericKubernetesResource.class)).inNamespace(NAMESPACE)
                .fieldManager("e2e").forceConflicts().serverSideApply();
    }

    /**
     * Copie un petit fichier dans le stockage du controller (PVC monté sur /data). Contenu passé en base64 dans la
     * commande : fermer l'entrée standard d'un exec fabric8 ne transmet pas de fin de fichier.
     */
    static void upload(String target, Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes.length).as("fichier trop gros pour exec : " + file).isLessThan(64 * 1024);
        Pod controller = CLIENT.pods().inNamespace(NAMESPACE).withLabel("app.kubernetes.io/component", "controller")
                .list().getItems().getFirst();
        String dir = target.substring(0, target.lastIndexOf('/'));
        String b64 = Base64.getEncoder().encodeToString(bytes);
        try (var exec = CLIENT.pods().inNamespace(NAMESPACE).withName(controller.getMetadata().getName())
                .inContainer("controller")
                .exec("sh", "-c", "mkdir -p '" + dir + "' && echo '" + b64 + "' | base64 -d > '" + target + "'")) {
            Integer code = exec.exitCode().get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(code).as("upload " + target).isZero();
        }
    }

    static HttpResponse<String> call(String method, String path, boolean authenticated) {
        return api.call(method, path, authenticated ? api.admin() : null, null);
    }

    static HttpResponse<String> get(String path) {
        try {
            return call("GET", path, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static KsmcModuleStatus status(String name) {
        KsmcModule m = CLIENT.resources(KsmcModule.class).inNamespace(NAMESPACE).withName(name).get();
        return m == null ? null : m.getStatus();
    }

    static void awaitPhase(String name, KsmcModuleStatus.Phase phase) {
        await("module " + name + " " + phase, Duration.ofMinutes(2), () -> {
            KsmcModuleStatus s = status(name);
            if (s != null && s.phase == KsmcModuleStatus.Phase.Failed && phase != KsmcModuleStatus.Phase.Failed) {
                throw new AssertionError(name + " failed: " + s.reason + " " + s.message);
            }
            return s != null && s.phase == phase;
        });
    }

    static void hello(String greeting, boolean enabled) {
        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: KsmcModule
                metadata: {name: hello, labels: {e2e: module}}
                spec:
                  source: {store: modules/hello.jar}
                  enabled: %s
                  config: {greeting: %s}
                """.formatted(enabled, greeting));
    }

    @Test
    @Order(1)
    void moduleIsInstalledServesRestAndReceivesConfigChanges() throws Exception {
        upload("/data/modules/hello.jar", MODULE_JAR);
        hello("Bonjour", true);
        awaitPhase("hello", KsmcModuleStatus.Phase.Enabled);
        assertThat(status("hello").loadedVersion).isEqualTo("1.0.0");

        HttpResponse<String> r = get("/api/v1/modules/hello/hello");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("Bonjour, admin !");
        assertThat(call("GET", "/api/v1/modules/hello/hello", false).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/modules/hello/cli").body()).contains("\"name\":\"broadcast\"");
        assertThat(get("/modules/hello/ui.js").body()).contains("export function mount");

        hello("Salut", true);
        await("nouvelle configuration", Duration.ofSeconds(30), () -> get("/api/v1/modules/hello/hello").body()
                .contains("Salut, admin !"));
    }

    @Test
    @Order(2)
    void invalidChecksumIsRejected() {
        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: KsmcModule
                metadata: {name: badsum, labels: {e2e: module}}
                spec:
                  source:
                    url: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
                    sha256: "%s"
                """.formatted("0".repeat(64)));
        awaitPhase("badsum", KsmcModuleStatus.Phase.Failed);
        assertThat(status("badsum").reason).isEqualTo("ChecksumMismatch");
    }

    @Test
    @Order(3)
    void pluginReceivesTheModuleBroadcastAndPodsAreLabelled() throws Exception {
        upload("/data/templates/e2em-plugin/plugins/ksmc-plugin-hello.jar", PLUGIN_JAR);
        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTemplate
                metadata: {name: e2em-plugin, labels: {e2e: module}}
                spec:
                  storage: {path: e2em-plugin}
                """);
        apply("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTask
                metadata: {name: e2em-paper, labels: {e2e: module}}
                spec:
                  environment: server
                  software: {name: paper, version: "1.21.4"}
                  memoryMb: 1024
                  minServiceCount: 1
                  templates: [{name: e2em-plugin}]
                """);
        String svc = "e2em-paper-1";
        await("service " + svc + " Running", TIMEOUT, () -> {
            CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(svc).get();
            return s != null && s.getStatus() != null && s.getStatus().phase == io.ksmc.api.model.CloudServiceStatus.Phase.Running;
        });
        Pod pod = CLIENT.pods().inNamespace(NAMESPACE).withName(svc).get();
        assertThat(pod.getMetadata().getLabels()).containsEntry("example.ksmc.io/hello", "true");

        await("plugin activé", Duration.ofSeconds(60), () -> logs(svc).contains("Service Ksmc : " + svc));
        await("propriété du plugin", Duration.ofSeconds(60), () -> {
            CloudService s = CLIENT.resources(CloudService.class).inNamespace(NAMESPACE).withName(svc).get();
            return "hello".equals(s.getStatus().properties.get("example"));
        });

        HttpResponse<String> sent = call("POST", "/api/v1/modules/hello/broadcast?text=coucou-e2e", true);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        await("message reçu par le plugin", Duration.ofSeconds(30), () -> logs(svc).contains("[hello] reçu : coucou-e2e"));
    }

    @Test
    @Order(4)
    void disabledModuleRoutesAnswer404() {
        hello("Salut", false);
        awaitPhase("hello", KsmcModuleStatus.Phase.Disabled);
        assertThat(get("/api/v1/modules/hello/hello").statusCode()).isEqualTo(404);
    }

    static String logs(String pod) {
        try {
            return CLIENT.pods().inNamespace(NAMESPACE).withName(pod).inContainer("server").getLog();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
