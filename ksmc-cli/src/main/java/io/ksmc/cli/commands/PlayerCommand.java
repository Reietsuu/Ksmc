package io.ksmc.cli.commands;

import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Output;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "player", description = "Joueurs connectés", subcommands = {PlayerCommand.ListCmd.class,
        PlayerCommand.Get.class, PlayerCommand.Send.class, PlayerCommand.Kick.class, PlayerCommand.Msg.class,
        PlayerCommand.Title.class})
public final class PlayerCommand extends BaseCommand {

    static final Output.Columns COLUMNS = new Output.Columns().name(n -> n.path("name").asText()).col("NOM", "/name")
            .col("UUID", "/uuid").col("PROXY", "/proxy").col("SERVEUR", "/server");

    @Override
    protected void run() {
        spec.commandLine().usage(out());
    }

    static String url(String player, String action) {
        return "/api/v1/players/" + Api.encode(player) + (action == null ? "" : "/" + action);
    }

    @Command(name = "list", aliases = "ls", description = "Lister")
    public static final class ListCmd extends BaseCommand {
        @Mixin
        Output output;

        @Override
        protected void run() {
            output.list(out(), items(api().get("/api/v1/players")), COLUMNS);
        }
    }

    @Command(name = "get", description = "Afficher un joueur (nom ou UUID)")
    public static final class Get extends BaseCommand {
        @Parameters(paramLabel = "JOUEUR")
        String player;
        @Mixin
        Output output;

        @Override
        protected void run() {
            output.one(out(), api().get(url(player, null)), COLUMNS);
        }
    }

    @Command(name = "send", description = "Envoyer un joueur vers un service ou une task")
    public static final class Send extends BaseCommand {
        @Parameters(paramLabel = "JOUEUR")
        String player;
        @Option(names = {"-s", "--service"})
        String service;
        @Option(names = {"-t", "--task"})
        String task;

        @Override
        protected void run() {
            if ((service == null) == (task == null)) {
                throw CliException.usage("--service ou --task (un seul)");
            }
            Map<String, String> body = new LinkedHashMap<>();
            if (service != null) {
                body.put("service", service);
            } else {
                body.put("task", task);
            }
            api().post(url(player, "send"), body);
            out().println(player + " envoyé vers " + (service != null ? service : task));
        }
    }

    @Command(name = "kick", description = "Expulser")
    public static final class Kick extends BaseCommand {
        @Parameters(index = "0", paramLabel = "JOUEUR")
        String player;
        @Parameters(index = "1..*", paramLabel = "RAISON", arity = "0..*")
        List<String> reason;

        @Override
        protected void run() {
            api().post(url(player, "kick"), reason == null ? Map.of() : Map.of("reason", String.join(" ", reason)));
            out().println(player + " expulsé");
        }
    }

    @Command(name = "msg", description = "Envoyer un message")
    public static final class Msg extends BaseCommand {
        @Parameters(index = "0", paramLabel = "JOUEUR")
        String player;
        @Parameters(index = "1..*", paramLabel = "TEXTE", arity = "1..*")
        List<String> text;

        @Override
        protected void run() {
            api().post(url(player, "message"), Map.of("text", String.join(" ", text)));
            out().println("message envoyé à " + player);
        }
    }

    @Command(name = "title", description = "Afficher un titre")
    public static final class Title extends BaseCommand {
        @Parameters(index = "0", paramLabel = "JOUEUR")
        String player;
        @Parameters(index = "1", paramLabel = "TITRE")
        String title;
        @Option(names = "--subtitle")
        String subtitle;

        @Override
        protected void run() {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("title", title);
            if (subtitle != null) {
                body.put("subtitle", subtitle);
            }
            api().post(url(player, "title"), body);
            out().println("titre affiché à " + player);
        }
    }
}
