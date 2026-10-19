package io.ksmc.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleStatus.Phase;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.spi.ScalingStrategy;
import io.ksmc.controller.module.DefaultModuleContext;
import io.ksmc.controller.module.ModuleHost;
import io.ksmc.controller.module.ModuleSources;
import io.ksmc.controller.module.ModuleTestJars;
import io.ksmc.controller.service.ArtifactCache;
import io.ksmc.controller.storage.FileArtifactStorage;
import io.ksmc.controller.template.Digests;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class KsmcModuleReconcilerTest {
    KubernetesClient client;

    @TempDir
    Path tmp;
    HttpServer http;
    byte[] helloJar;
    ExtensionRegistry registry;
    ModuleHost host;
    KsmcModuleReconciler reconciler;
    FileArtifactStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        helloJar = Files.readAllBytes(ModuleTestJars.greeter(tmp, "hello", "1.0.0"));
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/hello.jar", ex -> {
            ex.sendResponseHeaders(200, helloJar.length);
            ex.getResponseBody().write(helloJar);
            ex.close();
        });
        http.start();
        storage = new FileArtifactStorage(tmp.resolve("data"));
        registry = new ExtensionRegistry();
        host = new ModuleHost(registry, (d, scope, cfg, loader) -> new DefaultModuleContext(d, scope, cfg, loader, client,
                "test", () -> null, tmp.resolve("module-data")));
        Path builtin = Files.createDirectories(tmp.resolve("builtin"));
        Files.copy(ModuleTestJars.greeter(tmp, "core", "2.0.0"), builtin.resolve("core.jar"));
        ModuleSources sources = new ModuleSources(storage, new ArtifactCache(storage, HttpClient.newHttpClient()),
                HttpClient.newHttpClient(), tmp.resolve("jars"), builtin);
        reconciler = new KsmcModuleReconciler(client, "test", host, sources);
    }

    @AfterEach
    void tearDown() {
        http.stop(0);
        host.disableAll();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/hello.jar";
    }

    KsmcModule apply(String name, String specYaml) {
        KsmcModule desired = Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: KsmcModule
                metadata: {name: %s, namespace: test}
                spec:
                %s
                """.formatted(name, specYaml.indent(2)), KsmcModule.class);
        var resource = client.resources(KsmcModule.class).inNamespace("test").withName(name);
        if (resource.get() == null) {
            return client.resource(desired).create();
        }
        return resource.edit(m -> {
            m.setSpec(desired.getSpec());
            m.getMetadata().setGeneration(m.getMetadata().getGeneration() == null ? 2 : m.getMetadata().getGeneration() + 1);
            return m;
        });
    }

    /** Réconcilie jusqu'à sortir de Pending (téléchargement asynchrone) et écrit le statut comme JOSDK. */
    KsmcModule reconcile(String name) throws Exception {
        for (int i = 0; i < 100; i++) {
            KsmcModule m = client.resources(KsmcModule.class).inNamespace("test").withName(name).get();
            UpdateControl<KsmcModule> c = reconciler.reconcile(m);
            if (c.isPatchStatus()) {
                client.resource(c.getResource().orElseThrow()).patchStatus();
            }
            m = c.getResource().orElse(m);
            if (m.getStatus() != null && m.getStatus().phase != Phase.Pending) {
                return client.resources(KsmcModule.class).inNamespace("test").withName(name).get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("module " + name + " toujours en attente");
    }

    List<String> greetings() {
        return registry.all(ScalingStrategy.class).stream().map(ScalingStrategy::name).toList();
    }

    @Test
    void urlModuleIsDownloadedVerifiedEnabledAndReconfigured() throws Exception {
        String sha = Digests.hex(Digests.sha256().digest(helloJar));
        apply("hello", """
                source: {url: '%s', sha256: %s}
                config: {greeting: salut}
                """.formatted(url(), sha));

        KsmcModule m = reconcile("hello");

        assertThat(m.getStatus().phase).isEqualTo(Phase.Enabled);
        assertThat(m.getStatus().loadedVersion).isEqualTo("1.0.0");
        assertThat(greetings()).containsExactly("hello:salut");

        apply("hello", """
                source: {url: '%s', sha256: %s}
                config: {greeting: bonjour}
                """.formatted(url(), sha));
        reconcile("hello");

        assertThat(greetings()).containsExactly("hello:bonjour");
    }

    @Test
    void checksumMismatchFailsWithoutLoading() throws Exception {
        apply("hello", "source: {url: '%s', sha256: %s}".formatted(url(), "0".repeat(64)));

        KsmcModule m = reconcile("hello");

        assertThat(m.getStatus().phase).isEqualTo(Phase.Failed);
        assertThat(m.getStatus().reason).isEqualTo("ChecksumMismatch");
        assertThat(greetings()).isEmpty();
    }

    @Test
    void storeSourceReloadsWhenTheFileChanges() throws Exception {
        storage.write("modules/hello.jar", new ByteArrayInputStream(helloJar));
        apply("hello", "source: {store: modules/hello.jar}");
        assertThat(reconcile("hello").getStatus().loadedVersion).isEqualTo("1.0.0");

        Thread.sleep(1100);
        storage.write("modules/hello.jar", Files.newInputStream(ModuleTestJars.greeter(tmp, "hello", "1.1.0")));
        KsmcModule m = reconcile("hello");

        assertThat(m.getStatus().loadedVersion).isEqualTo("1.1.0");
        assertThat(greetings()).containsExactly("hello:none");
    }

    @Test
    void builtinModuleCanBeDisabledAndResourceDeletionUnloads() throws Exception {
        apply("core", "source: {builtin: core}");
        assertThat(reconcile("core").getStatus().phase).isEqualTo(Phase.Enabled);
        assertThat(greetings()).containsExactly("core:none");

        apply("core", "source: {builtin: core}\nenabled: false");
        assertThat(reconcile("core").getStatus().phase).isEqualTo(Phase.Disabled);
        assertThat(greetings()).isEmpty();

        apply("core", "source: {builtin: core}\nenabled: true");
        reconcile("core");
        client.resources(KsmcModule.class).inNamespace("test").withName("core").delete();
        reconciler.sweep();
        assertThat(greetings()).isEmpty();
    }

    @Test
    void unknownBuiltinAndNameMismatchFail() throws Exception {
        apply("ghost", "source: {builtin: ghost}");
        assertThat(reconcile("ghost").getStatus().reason).isEqualTo("DownloadError");

        storage.write("modules/hello.jar", new ByteArrayInputStream(helloJar));
        apply("other", "source: {store: modules/hello.jar}");
        KsmcModule m = reconcile("other");
        assertThat(m.getStatus().reason).isEqualTo("InvalidDescriptor");
        assertThat(m.getStatus().conditions).anySatisfy(c -> assertThat(c.getStatus()).isEqualTo("False"));
    }

    @Test
    void enabledModulesAreRecheckedPeriodically() throws Exception {
        apply("core", "source: {builtin: core}");
        KsmcModule m = reconcile("core");
        UpdateControl<KsmcModule> again = reconciler.reconcile(m);

        assertThat(again.isPatchStatus()).isFalse();
        assertThat(again.getScheduleDelay()).contains(KsmcModuleReconciler.RECHECK.toMillis());
    }
}
