package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Output;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * Groupe de commandes d'un type de ressource ({@code /api/v1/<path>}) ; les sous-commandes génériques (list, get,
 * apply, edit, delete) lisent le type dans leur parent.
 */
public abstract class ResourceGroup extends BaseCommand {

    /** Chemin de l'API ({@code tasks}, {@code services}...). */
    protected abstract String path();

    protected abstract Output.Columns columns();

    @Override
    protected void run() {
        spec.commandLine().usage(out());
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Toutes les pages d'une liste. */
    static List<JsonNode> listAll(Api api, String path, Map<String, String> filters) {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, String> q = new LinkedHashMap<>(filters);
            q.put("limit", "500");
            q.put("continue", cursor);
            JsonNode page = api.get("/api/v1/" + path + Api.query(q));
            page.path("items").forEach(all::add);
            JsonNode next = page.path("continue");
            cursor = next.isTextual() && !next.asText().isEmpty() ? next.asText() : null;
        } while (cursor != null);
        return all;
    }

    /** Lit un fichier YAML ou JSON (ou {@code -} : entrée standard) ; plusieurs documents YAML acceptés. */
    static List<JsonNode> readDocuments(String file) throws IOException {
        String text = file.equals("-") ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(file));
        List<JsonNode> docs = new ArrayList<>();
        try (var parser = io.ksmc.cli.Config.YAML_FACTORY.createParser(text)) {
            var it = Api.JSON.readValues(parser, JsonNode.class);
            while (it.hasNext()) {
                JsonNode n = it.next();
                if (n != null && !n.isNull() && !n.isMissingNode() && n.isObject()) {
                    docs.add(n);
                }
            }
        }
        if (docs.isEmpty()) {
            throw CliException.usage("aucune ressource dans " + file);
        }
        return docs;
    }

    @Command(name = "list", description = "Lister")
    public static final class ListCmd extends BaseCommand {
        @ParentCommand
        ResourceGroup parent;
        @Mixin
        Output output;
        @Option(names = {"-t", "--task"}, description = "Filtrer par task")
        String task;
        @Option(names = {"-l", "--selector"}, description = "Sélecteur de labels (ex. tier=hub)")
        String selector;

        @Override
        protected void run() {
            output.list(out(), listAll(api(), parent.path(), params("task", task, "labelSelector", selector)),
                    parent.columns());
        }
    }

    @Command(name = "get", description = "Afficher une ressource (YAML par défaut)")
    public static final class GetCmd extends BaseCommand {
        @ParentCommand
        ResourceGroup parent;
        @Option(names = {"-o", "--output"}, description = "yaml (défaut), json, table, name", defaultValue = "yaml")
        Output.Format format;
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() {
            Output o = new Output();
            o.format(format);
            o.one(out(), api().get("/api/v1/" + parent.path() + "/" + Api.encode(name)), parent.columns());
        }
    }

    @Command(name = "apply", description = "Créer ou remplacer depuis un fichier YAML/JSON (-f -, entrée standard)")
    public static final class ApplyCmd extends BaseCommand {
        @ParentCommand
        ResourceGroup parent;
        @Option(names = {"-f", "--file"}, required = true, paramLabel = "FICHIER")
        String file;

        @Override
        protected void run() throws IOException {
            for (JsonNode doc : readDocuments(file)) {
                String name = doc.at("/metadata/name").asText(null);
                if (name == null) {
                    throw CliException.usage("metadata.name manquant dans " + file);
                }
                ((ObjectNode) doc).remove("status");
                String base = "/api/v1/" + parent.path();
                try {
                    api().post(base, doc);
                    out().println(parent.path() + "/" + name + " créé");
                } catch (CliException e) {
                    if (!e.getMessage().startsWith("Conflict")) {
                        throw e;
                    }
                    api().put(base + "/" + Api.encode(name), doc);
                    out().println(parent.path() + "/" + name + " mis à jour");
                }
            }
        }
    }

    @Command(name = "edit", description = "Modifier dans $EDITOR (YAML), avec contrôle de concurrence")
    public static final class EditCmd extends BaseCommand {
        @ParentCommand
        ResourceGroup parent;
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() throws Exception {
            String url = "/api/v1/" + parent.path() + "/" + Api.encode(name);
            ObjectNode current = (ObjectNode) api().get(url);
            String version = current.at("/metadata/resourceVersion").asText();
            current.remove("status");
            Path tmp = Files.createTempFile("ksmc-" + name, ".yaml");
            try {
                String before = io.ksmc.cli.Config.YAML.writeValueAsString(current);
                Files.writeString(tmp, before);
                String editor = System.getenv().getOrDefault("VISUAL", System.getenv().getOrDefault("EDITOR", "vi"));
                int code = new ProcessBuilder("sh", "-c", editor + " \"$1\"", "editor", tmp.toString()).inheritIO()
                        .start().waitFor();
                if (code != 0) {
                    throw CliException.usage("éditeur terminé avec le code " + code);
                }
                String after = Files.readString(tmp);
                if (after.equals(before)) {
                    out().println("aucune modification");
                    return;
                }
                JsonNode edited = io.ksmc.cli.Config.YAML.readTree(after);
                api().call("PUT", url, edited, Map.of("If-Match", "\"" + version + "\""));
                out().println(parent.path() + "/" + name + " modifié");
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Command(name = "delete", description = "Supprimer")
    public static final class DeleteCmd extends BaseCommand {
        @ParentCommand
        ResourceGroup parent;
        @Parameters(paramLabel = "NOM", arity = "1..*")
        List<String> names;

        @Override
        protected void run() {
            for (String n : names) {
                api().delete("/api/v1/" + parent.path() + "/" + Api.encode(n));
                out().println(parent.path() + "/" + n + " supprimé");
            }
        }
    }
}
