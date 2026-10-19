package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Output;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "task", description = "Tasks (ServiceTask)", subcommands = {ResourceGroup.ListCmd.class,
        ResourceGroup.GetCmd.class, TaskCommand.Create.class, ResourceGroup.ApplyCmd.class, ResourceGroup.EditCmd.class,
        ResourceGroup.DeleteCmd.class, TaskCommand.Start.class, TaskCommand.Maintenance.class})
public final class TaskCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "tasks";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("ENV", "/spec/environment")
                .col("LOGICIEL", n -> Output.text(n.at("/spec/software/name"))
                        + (n.at("/spec/software/version").isTextual() ? ":" + n.at("/spec/software/version").asText() : ""))
                .col("MIN", "/spec/minServiceCount").col("MAX", "/spec/maxServiceCount").col("PRÊTS", "/status/ready")
                .col("JOUEURS", "/status/onlinePlayers").col("ÂGE", n -> Output.age(n.at("/metadata/creationTimestamp")));
    }

    @Command(name = "create", description = "Créer une task simple (pour tout le reste : apply -f)")
    public static final class Create extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;
        @Option(names = {"-s", "--software"}, required = true, description = "Logiciel[:version], ex. paper:1.21.4")
        String software;
        @Option(names = {"-e", "--environment"}, description = "server (défaut) ou proxy", defaultValue = "server")
        String environment;
        @Option(names = "--min", description = "minServiceCount (défaut 1)", defaultValue = "1")
        int min;
        @Option(names = "--max", description = "maxServiceCount")
        Integer max;
        @Option(names = {"-m", "--memory"}, description = "Mémoire en Mio")
        Integer memory;
        @Option(names = "--static", description = "Services statiques (nom et données conservés)")
        boolean staticServices;

        @Override
        protected void run() {
            ObjectNode spec = Api.JSON.createObjectNode().put("environment", environment).put("minServiceCount", min);
            String[] sw = software.split(":", 2);
            ObjectNode s = spec.putObject("software").put("name", sw[0]);
            if (sw.length == 2) {
                s.put("version", sw[1]);
            }
            if (max != null) {
                spec.put("maxServiceCount", max);
            }
            if (memory != null) {
                spec.put("memoryMb", memory);
            }
            if (staticServices) {
                spec.put("static", true);
            }
            ObjectNode body = Api.JSON.createObjectNode();
            body.putObject("metadata").put("name", name);
            body.set("spec", spec);
            api().post("/api/v1/tasks", body);
            out().println("tasks/" + name + " créé");
        }
    }

    @Command(name = "start", description = "Démarrer des services en plus du minimum (affiche leurs noms)")
    public static final class Start extends BaseCommand {
        @Parameters(paramLabel = "TASK")
        String task;
        @Option(names = {"-n", "--count"}, description = "Nombre de services (défaut 1)", defaultValue = "1")
        int count;

        @Override
        protected void run() {
            JsonNode r = api().post("/api/v1/tasks/" + Api.encode(task) + "/start", Map.of("count", count));
            r.path("services").forEach(n -> out().println(n.asText()));
            if (r.path("started").asInt() < count) {
                err().println("plafonné par maxServiceCount : " + r.path("started").asInt() + "/" + count + " démarré(s)");
            }
        }
    }

    @Command(name = "maintenance", description = "Activer ou désactiver la maintenance")
    public static final class Maintenance extends BaseCommand {
        @Parameters(index = "0", paramLabel = "TASK")
        String task;
        @Parameters(index = "1", paramLabel = "on|off")
        String state;

        @Override
        protected void run() {
            boolean on = switch (state) {
                case "on", "true" -> true;
                case "off", "false" -> false;
                default -> throw CliException.usage("on ou off attendu : " + state);
            };
            api().post("/api/v1/tasks/" + Api.encode(task) + "/maintenance", Map.of("enabled", on));
            out().println("maintenance " + (on ? "activée" : "désactivée") + " pour " + task);
        }
    }
}
