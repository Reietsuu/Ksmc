package io.ksmc.cli.commands;

import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.Output;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "service", description = "Services (CloudService)", subcommands = {ResourceGroup.ListCmd.class,
        ResourceGroup.GetCmd.class, ResourceGroup.ApplyCmd.class, ResourceGroup.DeleteCmd.class, ServiceCommand.Stop.class,
        ServiceCommand.StartCmd.class, ServiceCommand.Restart.class, ServiceCommand.Cmd.class, ServiceCommand.Deploy.class,
        ConsoleCommand.class, LogsCommand.class})
public final class ServiceCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "services";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("TASK", "/spec/task").col("PHASE", "/status/phase")
                .col("JOUEURS", "/status/players").col("ÉTAT", "/status/state").col("ADRESSE", "/status/address")
                .col("ÂGE", n -> Output.age(n.at("/metadata/creationTimestamp")));
    }

    abstract static class Action extends BaseCommand {
        @Parameters(index = "0", paramLabel = "SERVICE")
        String name;

        void post(String action, Object body, String done) {
            api().post("/api/v1/services/" + Api.encode(name) + "/" + action, body);
            out().println(done);
        }
    }

    @Command(name = "stop", description = "Arrêter (un service dynamique est ensuite remplacé si le minimum l'exige)")
    public static final class Stop extends Action {
        @Override
        protected void run() {
            post("stop", null, name + " : arrêt demandé");
        }
    }

    @Command(name = "start", description = "Démarrer un service statique arrêté")
    public static final class StartCmd extends Action {
        @Override
        protected void run() {
            post("start", null, name + " : démarrage demandé");
        }
    }

    @Command(name = "restart", description = "Redémarrer")
    public static final class Restart extends Action {
        @Override
        protected void run() {
            post("restart", null, name + " : redémarrage demandé");
        }
    }

    @Command(name = "cmd", description = "Envoyer une commande console, ex. ksmc service cmd lobby-1 say Bonjour")
    public static final class Cmd extends Action {
        @Parameters(index = "1..*", paramLabel = "COMMANDE", arity = "1..*")
        List<String> command;

        @Override
        protected void run() {
            post("command", Map.of("command", String.join(" ", command)), "commande envoyée à " + name);
        }
    }

    @Command(name = "deploy", description = "Copier le dossier du service dans un template storage")
    public static final class Deploy extends Action {
        @Parameters(index = "1", paramLabel = "TEMPLATE", description = "Chemin du template, ex. lobby/backup")
        String template;

        @Override
        protected void run() {
            post("deploy", Map.of("template", template), name + " : sauvegarde vers " + template + " lancée");
        }
    }
}
