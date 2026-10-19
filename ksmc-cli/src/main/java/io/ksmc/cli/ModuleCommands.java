package io.ksmc.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Commandes déclarées par les modules du controller ({@code GET /api/v1/cli/commands}), exposées sous
 * {@code ksmc <module> <commande> [arguments]} sans mise à jour de la CLI. Liste en cache 10 minutes par contexte
 * ({@code ~/.ksmc/cache/}) ; un controller injoignable n'empêche pas la CLI de fonctionner.
 */
final class ModuleCommands {

    static final Duration CACHE = Duration.ofMinutes(10);

    private ModuleCommands() {
    }

    /** Vrai si les commandes de modules sont utiles pour ces arguments (commande inconnue, aide, complétion). */
    static boolean wanted(CommandLine cl, String[] args) {
        String first = null;
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                return true;
            }
            if (!a.startsWith("-") && first == null) {
                first = a;
            }
        }
        return first == null || first.equals("completion") || !cl.getSubcommands().containsKey(first);
    }

    static void register(CommandLine cl, Main main, String[] args) {
        if (!wanted(cl, args)) {
            return;
        }
        JsonNode commands;
        try {
            commands = load(main);
        } catch (RuntimeException e) {
            return;
        }
        if (commands == null) {
            return;
        }
        commands.properties().forEach(module -> {
            if (cl.getSubcommands().containsKey(module.getKey())) {
                return; // jamais masquer une commande intégrée
            }
            CommandLine[] holder = new CommandLine[1];
            CommandSpec group = CommandSpec.wrapWithoutInspection((Runnable) () -> holder[0].usage(holder[0].getOut()));
            group.name(module.getKey()).usageMessage().description("Commandes du module " + module.getKey());
            CommandLine groupLine = new CommandLine(group);
            holder[0] = groupLine;
            for (JsonNode c : module.getValue()) {
                groupLine.addSubcommand(c.path("name").asText(), command(main, module.getKey(), c));
            }
            cl.addSubcommand(module.getKey(), groupLine);
        });
    }

    /** Liste depuis le cache du contexte, rechargée après {@link #CACHE}. */
    static JsonNode load(Main main) {
        String context = main.config().contextName(main.contextName());
        Path cache = context == null ? null : Config.path().toAbsolutePath().getParent().resolve("cache")
                .resolve(context.replaceAll("[^A-Za-z0-9._-]", "_") + "-cli-commands.json");
        try {
            if (cache != null && Files.isRegularFile(cache)) {
                JsonNode cached = Api.JSON.readTree(cache.toFile());
                Instant at = Instant.parse(cached.path("fetchedAt").asText("1970-01-01T00:00:00Z"));
                if (Instant.now().isBefore(at.plus(CACHE))) {
                    return cached.path("commands");
                }
            }
            JsonNode fresh = main.api().get("/api/v1/cli/commands");
            if (cache != null) {
                ObjectNode entry = Api.JSON.createObjectNode().put("fetchedAt", Instant.now().toString());
                entry.set("commands", fresh);
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, Api.JSON.writeValueAsString(entry));
            }
            return fresh;
        } catch (IOException e) {
            return null;
        }
    }

    static CommandLine command(Main main, String module, JsonNode c) {
        List<JsonNode> args = new ArrayList<>();
        c.path("args").forEach(args::add);
        List<PositionalParamSpec> params = new ArrayList<>();
        Callable<Integer> action = () -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.size(); i++) {
                Object v = params.get(i).getValue();
                if (v != null) {
                    values.put(args.get(i).path("name").asText(), v.toString());
                }
            }
            String path = c.path("path").asText("");
            Map<String, String> query = new LinkedHashMap<>();
            for (var e : values.entrySet()) {
                String placeholder = "{" + e.getKey() + "}";
                if (path.contains(placeholder)) {
                    path = path.replace(placeholder, Api.encode(e.getValue()));
                } else {
                    query.put(e.getKey(), e.getValue());
                }
            }
            if (path.matches(".*\\{[^}]+}.*")) {
                throw CliException.usage("argument manquant pour " + path);
            }
            String url = "/api/v1/modules/" + Api.encode(module) + "/" + path.replaceFirst("^/", "") + Api.query(query);
            JsonNode r = main.api().call(c.path("httpMethod").asText("GET").toUpperCase(), url, null, Map.of());
            var out = main.out();
            if (r.isTextual()) {
                out.println(r.asText());
            } else if (!r.isEmpty()) {
                Output.write(out, r, false);
            }
            out.flush();
            return 0;
        };
        CommandSpec spec = CommandSpec.wrapWithoutInspection(action);
        spec.name(c.path("name").asText()).usageMessage().description(c.path("description").asText(""));
        for (int i = 0; i < args.size(); i++) {
            JsonNode a = args.get(i);
            PositionalParamSpec p = PositionalParamSpec.builder().index(String.valueOf(i))
                    .paramLabel(a.path("name").asText().toUpperCase()).required(a.path("required").asBoolean())
                    .arity(a.path("required").asBoolean() ? "1" : "0..1")
                    .description(a.path("description").asText("")).type(String.class).build();
            params.add(p);
            spec.addPositional(p);
        }
        CommandLine line = new CommandLine(spec);
        return line;
    }
}
