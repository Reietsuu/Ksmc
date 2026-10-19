package io.ksmc.controller.module;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.api.module.ModuleContext;
import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.Registry;
import java.util.function.Supplier;

/** Fabrique le contexte donné à un module à son activation. */
@FunctionalInterface
public interface ModuleContextFactory {

    /** @param classLoader classloader du module (ressources du JAR, ex. bundle du dashboard) */
    ModuleContext create(ModuleDescriptor descriptor, Registry registry, Supplier<JsonNode> config, ClassLoader classLoader);
}
