package io.ksmc.controller.module;

import java.nio.file.Path;
import java.util.Map;

/** JAR de modules prêts à l'emploi pour les tests d'autres paquets. */
public final class ModuleTestJars {

    private ModuleTestJars() {
    }

    /** Module qui enregistre une stratégie de scaling nommée {@code <id>:<config.greeting>} (suivant la config). */
    public static Path greeter(Path dir, String id, String version) throws Exception {
        String pkg = "g_" + id.replace('-', '_') + "_" + version.replace('.', '_');
        return TestModules.jar(dir, TestModules.descriptor(id, version, pkg + ".Main", null), Map.of(pkg + ".Main", """
                package %s;
                import io.ksmc.api.module.*;
                import io.ksmc.api.spi.ScalingStrategy;
                public class Main implements KsmcModule {
                    volatile String greeting;
                    public void onEnable(ModuleContext ctx) {
                        greeting = ctx.config().path("greeting").asText("none");
                        ctx.registry().register(ScalingStrategy.class, new ScalingStrategy() {
                            public String name() { return "%s:" + greeting; }
                            public int desiredServices(Context c) { return NO_OPINION; }
                        });
                    }
                    public void onConfigChanged(com.fasterxml.jackson.databind.JsonNode cfg) {
                        greeting = cfg.path("greeting").asText("none");
                    }
                }
                """.formatted(pkg, id)));
    }
}
