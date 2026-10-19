package io.ksmc.controller.module;

import io.ksmc.api.module.CliCommand;
import io.ksmc.api.module.DashboardPage;
import io.ksmc.api.module.RestRoutes;

/** Extensions d'interface enregistrées par les modules dans l'{@code ExtensionRegistry} (servies par l'API REST). */
public final class ModuleExtensions {

    private ModuleExtensions() {
    }

    /** Route sous {@code /api/v1/modules/<moduleId>/<path>}. */
    public record Route(String moduleId, RestRoutes.Method method, String path, String permission,
            RestRoutes.Handler handler, ClassLoader classLoader) {
    }

    public record Cli(String moduleId, CliCommand command) {
    }

    public record Page(String moduleId, DashboardPage page, ClassLoader classLoader) {
    }
}
