package io.ksmc.api.module;

import java.util.List;

/**
 * Commande CLI déclarative : {@code ksmc <module> <name> args...} appelle {@code httpMethod} sur
 * {@code /api/v1/modules/<id>/<path>}, où {@code {arg}} dans le chemin est remplacé par l'argument du même nom ;
 * les autres arguments sont passés en paramètres de requête.
 */
public record CliCommand(String name, String description, List<Argument> args, String httpMethod, String path) {

    public CliCommand {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public record Argument(String name, String description, boolean required) {
    }
}
