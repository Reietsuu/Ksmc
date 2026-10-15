package io.ksmc.controller.http;

import io.javalin.config.RoutesConfig;

/** Groupe de routes HTTP enregistré sur le serveur du controller (API interne, REST, modules...). */
@FunctionalInterface
public interface HttpRoutes {
    void register(RoutesConfig routes);
}
