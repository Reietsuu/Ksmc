package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Output;
import java.util.Map;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "module", description = "Modules du controller (KsmcModule)", subcommands = {ResourceGroup.ListCmd.class,
        ResourceGroup.GetCmd.class, ModuleCommand.Install.class, ModuleCommand.ConfigCmd.class, ModuleCommand.Enable.class,
        ModuleCommand.Disable.class, ResourceGroup.ApplyCmd.class, ModuleCommand.Remove.class})
public final class ModuleCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "modules";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("VERSION", "/status/loadedVersion")
                .col("PHASE", "/status/phase").col("RAISON", "/status/reason").col("MESSAGE", "/status/message");
    }

    /** {@code clé=valeur} → objet JSON (valeurs numériques et booléennes reconnues, clés pointées imbriquées). */
    static ObjectNode config(Map<String, String> values, ObjectNode into) {
        values.forEach((k, v) -> {
            ObjectNode node = into;
            String[] parts = k.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                node = node.has(parts[i]) && node.get(parts[i]).isObject() ? (ObjectNode) node.get(parts[i])
                        : node.putObject(parts[i]);
            }
            String key = parts[parts.length - 1];
            if (v.equals("true") || v.equals("false")) {
                node.put(key, Boolean.parseBoolean(v));
            } else if (v.matches("-?\\d{1,18}")) {
                node.put(key, Long.parseLong(v));
            } else {
                node.put(key, v);
            }
        });
        return into;
    }

    static final class Source {
        @Option(names = "--url", description = "URL HTTPS du JAR (avec --sha256)")
        String url;
        @Option(names = "--maven", description = "Coordonnées groupe:artefact:version")
        String maven;
        @Option(names = "--store", description = "Fichier du stockage du controller, ex. modules/hello.jar")
        String store;
        @Option(names = "--builtin", description = "Module intégré à l'image")
        String builtin;
    }

    @Command(name = "install", description = "Installer un module (le nom est l'id du module)")
    public static final class Install extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;
        @ArgGroup(exclusive = true, multiplicity = "1")
        Source source;
        @Option(names = "--sha256", description = "Empreinte du JAR (obligatoire avec --url)")
        String sha256;
        @Option(names = "--repository", description = "Dépôt Maven (défaut : Maven Central)")
        String repository;
        @Option(names = {"-c", "--config"}, description = "Configuration clé=valeur (répétable)")
        Map<String, String> config = new java.util.LinkedHashMap<>();

        @Override
        protected void run() {
            ObjectNode body = Api.JSON.createObjectNode();
            body.putObject("metadata").put("name", name);
            ObjectNode spec = body.putObject("spec");
            ObjectNode src = spec.putObject("source");
            if (source.url != null) {
                if (sha256 == null) {
                    throw CliException.usage("--sha256 obligatoire avec --url");
                }
                src.put("url", source.url).put("sha256", sha256);
            } else if (source.maven != null) {
                src.put("maven", source.maven);
                if (repository != null) {
                    src.put("mavenRepository", repository);
                }
            } else if (source.store != null) {
                src.put("store", source.store);
            } else {
                src.put("builtin", source.builtin);
            }
            if (!config.isEmpty()) {
                spec.set("config", ModuleCommand.config(config, Api.JSON.createObjectNode()));
            }
            api().post("/api/v1/modules", body);
            out().println("modules/" + name + " installé (suivre : ksmc module list)");
        }
    }

    @Command(name = "config", description = "Modifier la configuration (clé=valeur ; transmise sans rechargement)")
    public static final class ConfigCmd extends BaseCommand {
        @Parameters(index = "0", paramLabel = "NOM")
        String name;
        @Parameters(index = "1..*", paramLabel = "CLÉ=VALEUR", arity = "1..*")
        Map<String, String> values;

        @Override
        protected void run() {
            ObjectNode patch = Api.JSON.createObjectNode();
            config(values, patch.putObject("spec").putObject("config"));
            api().call("PATCH", "/api/v1/modules/" + Api.encode(name), patch, Map.of());
            out().println("configuration de " + name + " mise à jour");
        }
    }

    abstract static class Toggle extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;

        void set(boolean enabled) {
            ObjectNode patch = Api.JSON.createObjectNode();
            patch.putObject("spec").put("enabled", enabled);
            JsonNode r = api().call("PATCH", "/api/v1/modules/" + Api.encode(name), patch, Map.of());
            out().println(name + (enabled ? " activé" : " désactivé") + " (phase : " + r.at("/status/phase").asText("?") + ")");
        }
    }

    @Command(name = "enable", description = "Activer")
    public static final class Enable extends Toggle {
        @Override
        protected void run() {
            set(true);
        }
    }

    @Command(name = "disable", description = "Désactiver (sans désinstaller)")
    public static final class Disable extends Toggle {
        @Override
        protected void run() {
            set(false);
        }
    }

    @Command(name = "remove", aliases = {"rm", "delete"}, description = "Désinstaller")
    public static final class Remove extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() {
            api().delete("/api/v1/modules/" + Api.encode(name));
            out().println("modules/" + name + " supprimé");
        }
    }
}
