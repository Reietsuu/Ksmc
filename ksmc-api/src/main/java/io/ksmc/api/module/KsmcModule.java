package io.ksmc.api.module;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Point d'entrée d'un module. La classe est désignée par {@code main} dans {@code ksmc-module.json} et doit avoir un
 * constructeur public sans argument.
 *
 * <p>Tout ce que le module enregistre via {@link ModuleContext} est retiré automatiquement à sa désactivation.
 * Une exception levée par une de ces méthodes met le module en échec sans affecter le controller.
 */
public interface KsmcModule {

    void onEnable(ModuleContext context) throws Exception;

    default void onDisable() throws Exception {
    }

    /** Nouvelle valeur de {@code KsmcModule.spec.config} (objet vide si absente), sans rechargement du module. */
    default void onConfigChanged(JsonNode config) throws Exception {
    }
}
