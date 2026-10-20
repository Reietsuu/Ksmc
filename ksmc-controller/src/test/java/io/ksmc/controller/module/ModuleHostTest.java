package io.ksmc.controller.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.ksmc.api.module.CliCommand;
import io.ksmc.api.module.DashboardPage;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.module.ModuleContext;
import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.Registry;
import io.ksmc.api.module.RestRoutes;
import io.ksmc.api.module.SemVer;
import io.ksmc.api.spi.ScalingStrategy;
import io.ksmc.wire.messaging.EventApi;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingTransport;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ModuleHostTest {

    @TempDir
    Path dir;
    ExtensionRegistry registry;
    ModuleHost host;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry();
        host = new ModuleHost(registry, (d, r, c, l) -> new TestContext(d, r, c));
    }

    /** Module dont l'activation enregistre une stratégie de scaling nommée par {@code nameExpr}. */
    Path module(String id, String version, String extraDescriptor, String nameExpr, Map<String, String> more,
            Path... cp) throws Exception {
        String pkg = id.replace('-', '_');
        Map<String, String> sources = new java.util.HashMap<>(more);
        sources.put(pkg + ".Main", """
                package %s;
                import io.ksmc.api.module.*;
                import io.ksmc.api.spi.ScalingStrategy;
                public class Main implements KsmcModule {
                    static volatile String greeting = "none";
                    public void onEnable(ModuleContext ctx) throws Exception {
                        greeting = ctx.config().path("greeting").asText("none");
                        ctx.registry().register(ScalingStrategy.class, new ScalingStrategy() {
                            public String name() { return %s; }
                            public int desiredServices(Context c) { return NO_OPINION; }
                        });
                    }
                    public void onConfigChanged(com.fasterxml.jackson.databind.JsonNode cfg) {
                        greeting = cfg.path("greeting").asText("none");
                    }
                }
                """.formatted(pkg, nameExpr));
        return TestModules.jar(dir, TestModules.descriptor(id, version, pkg + ".Main", extraDescriptor), sources, cp);
    }

    Path module(String id, String nameExpr) throws Exception {
        return module(id, "1.0.0", null, nameExpr, Map.of());
    }

    java.util.List<String> strategies() {
        return registry.all(ScalingStrategy.class).stream().map(ScalingStrategy::name).toList();
    }

    @Test
    void enableRegistersAndDisableRemovesEverything() throws Exception {
        host.enable(module("alpha", "\"alpha\""), null, "alpha");
        assertThat(strategies()).containsExactly("alpha");
        assertThat(host.activeVersions()).containsEntry("alpha", new SemVer(1, 0, 0, null));

        host.disable("alpha");

        assertThat(strategies()).isEmpty();
        assertThat(host.activeVersions()).isEmpty();
    }

    @Test
    void exceptionInOnEnableIsConfinedToTheModule() throws Exception {
        host.enable(module("good", "\"good\""), null, null);
        Path bad = TestModules.jar(dir, TestModules.descriptor("bad", "1.0.0", "bad.Main", null), Map.of("bad.Main", """
                package bad;
                public class Main implements io.ksmc.api.module.KsmcModule {
                    public void onEnable(io.ksmc.api.module.ModuleContext ctx) {
                        ctx.registry().register(io.ksmc.api.spi.ScalingStrategy.class, new io.ksmc.api.spi.ScalingStrategy() {
                            public String name() { return "leak"; }
                            public int desiredServices(Context c) { return NO_OPINION; }
                        });
                        throw new IllegalStateException("boom");
                    }
                }
                """));

        assertThatThrownBy(() -> host.enable(bad, null, null)).isInstanceOfSatisfying(ModuleException.class, e -> {
            assertThat(e.reason()).isEqualTo(ModuleException.ENABLE_ERROR);
            assertThat(e.getMessage()).contains("boom");
        });
        assertThat(strategies()).containsExactly("good");
        assertThat(host.activeVersions()).containsOnlyKeys("good");
    }

    @Test
    void incompatibleApiAndInvalidJarsAreRejected() throws Exception {
        Path future = module("future", "1.0.0", "\"apiVersion\":\"^2.0\"", "\"f\"", Map.of());
        assertThatThrownBy(() -> host.enable(future, null, null))
                .extracting(e -> ((ModuleException) e).reason()).isEqualTo(ModuleException.INCOMPATIBLE_API);

        Path noDescriptor = TestModules.jar(dir, null, Map.of("x.Y", "package x; public class Y {}"));
        assertThatThrownBy(() -> host.enable(noDescriptor, null, null))
                .extracting(e -> ((ModuleException) e).reason()).isEqualTo(ModuleException.INVALID_DESCRIPTOR);

        Path wrongId = module("other", "\"o\"");
        assertThatThrownBy(() -> host.enable(wrongId, null, "expected"))
                .extracting(e -> ((ModuleException) e).reason()).isEqualTo(ModuleException.INVALID_DESCRIPTOR);
    }

    @Test
    void dependenciesShareClassesAndCascadeOnDisable() throws Exception {
        Path base = module("base", "2.1.0", null, "base_lib.Lib.NAME",
                Map.of("base_lib.Lib", "package base_lib; public class Lib { public static final String NAME = \"base\"; }"));
        Path user = module("user", "1.0.0", "\"dependencies\":[{\"id\":\"base\",\"version\":\"^2.0\"}]",
                "\"user-of-\" + base_lib.Lib.class.getSimpleName()", Map.of(), base);

        assertThatThrownBy(() -> host.enable(user, null, null))
                .extracting(e -> ((ModuleException) e).reason()).isEqualTo(ModuleException.MISSING_DEPENDENCY);

        host.enable(base, null, null);
        host.enable(user, null, null);
        assertThat(strategies()).containsExactly("base", "user-of-Lib");

        assertThat(host.disable("base")).containsExactly("user");
        assertThat(strategies()).isEmpty();
    }

    @Test
    void conflictingLibrariesStayIsolated() throws Exception {
        String lib = "package shared; public class Version { public static String value() { return \"%s\"; } }";
        host.enable(module("one", "1.0.0", null, "shared.Version.value()", Map.of("shared.Version", lib.formatted("v1"))),
                null, null);
        host.enable(module("two", "1.0.0", null, "shared.Version.value()", Map.of("shared.Version", lib.formatted("v2"))),
                null, null);

        assertThat(strategies()).containsExactly("v1", "v2");
    }

    @Test
    void controllerInternalsAreHidden() throws Exception {
        String probe = """
                package probe;
                public class Probe {
                    public static boolean visible(String name) {
                        try {
                            Class.forName(name, false, Probe.class.getClassLoader());
                            return true;
                        } catch (ClassNotFoundException e) {
                            return false;
                        }
                    }
                }
                """;
        host.enable(module("probe", "1.0.0", null, """
                probe.Probe.visible("io.ksmc.controller.module.ModuleHost") + "/"
                        + probe.Probe.visible("io.ksmc.api.module.KsmcModule") + "/"
                        + probe.Probe.visible("io.javalin.Javalin")""", Map.of("probe.Probe", probe)), null, null);

        assertThat(strategies()).containsExactly("false/true/false");
    }

    @Test
    void configChangesReachTheModuleWithoutReload() throws Exception {
        ObjectMapper json = new ObjectMapper();
        host.enable(module("cfg", "cfg.Main.greeting"), json.readTree("{\"greeting\":\"hello\"}"), null);
        assertThat(strategies()).containsExactly("hello");

        host.configure("cfg", json.readTree("{\"greeting\":\"bonjour\"}"));

        assertThat(strategies()).containsExactly("bonjour");
    }

    @Test
    void repeatedReloadsLeaveNoRegistrations() throws Exception {
        Path jar = module("loop", "\"loop\"");
        for (int i = 0; i < 20; i++) {
            host.enable(jar, null, null);
        }
        assertThat(strategies()).containsExactly("loop");
        host.disableAll();
        assertThat(strategies()).isEmpty();
        assertThat(registry.count("loop")).isZero();
    }

    /** Contexte minimal : registre et configuration ; le reste n'est pas utilisé par ces tests. */
    record TestContext(ModuleDescriptor descriptor, Registry registry, Supplier<JsonNode> configSupplier)
            implements ModuleContext {
        public MessagingTransport messaging() {
            throw new UnsupportedOperationException();
        }

        public EventApi events() {
            throw new UnsupportedOperationException();
        }

        public KeyValueApi kv(String bucket) {
            throw new UnsupportedOperationException();
        }

        public KubernetesClient kubernetes() {
            throw new UnsupportedOperationException();
        }

        public String namespace() {
            return "test";
        }

        public RestRoutes rest() {
            throw new UnsupportedOperationException();
        }

        public void cliCommand(CliCommand command) {
        }

        public void dashboardPage(DashboardPage page) {
        }

        public JsonNode config() {
            return configSupplier.get();
        }

        public Path dataDirectory() {
            throw new UnsupportedOperationException();
        }

        public io.ksmc.api.module.ModuleMetrics metrics() {
            throw new UnsupportedOperationException();
        }

        public Logger logger() {
            return LoggerFactory.getLogger("module." + descriptor.id);
        }
    }
}
