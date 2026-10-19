package io.ksmc.cli.commands;

import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Config;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "context", description = "Contextes de connexion (~/.ksmc/config.yaml)",
        subcommands = {ContextCommand.ListCmd.class, ContextCommand.Use.class, ContextCommand.Current.class,
                ContextCommand.Delete.class})
public final class ContextCommand extends BaseCommand {

    @Override
    protected void run() {
        spec.commandLine().usage(out());
    }

    @Command(name = "list", aliases = "ls", description = "Lister les contextes")
    public static final class ListCmd extends BaseCommand {
        @Override
        protected void run() {
            Config c = main().config();
            out().printf("%-3s%-20s%-40s%s%n", "", "NOM", "SERVEUR", "UTILISATEUR");
            for (Map.Entry<String, Config.Context> e : c.contexts.entrySet()) {
                out().printf("%-3s%-20s%-40s%s%n", e.getKey().equals(c.current) ? "*" : "", e.getKey(),
                        e.getValue().server, e.getValue().user == null ? "" : e.getValue().user);
            }
        }
    }

    @Command(name = "use", description = "Changer de contexte courant")
    public static final class Use extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() throws Exception {
            Config c = main().config();
            if (!c.contexts.containsKey(name)) {
                throw CliException.usage("contexte inconnu : " + name);
            }
            c.current = name;
            c.save();
            out().println("contexte courant : " + name);
        }
    }

    @Command(name = "current", description = "Afficher le contexte courant")
    public static final class Current extends BaseCommand {
        @Override
        protected void run() {
            Config c = main().config();
            if (c.current == null) {
                throw CliException.usage("aucun contexte");
            }
            out().println(c.current);
        }
    }

    @Command(name = "delete", aliases = "rm", description = "Oublier un contexte (et ses jetons)")
    public static final class Delete extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() throws Exception {
            Config c = main().config();
            if (c.contexts.remove(name) == null) {
                throw CliException.usage("contexte inconnu : " + name);
            }
            if (name.equals(c.current)) {
                c.current = c.contexts.isEmpty() ? null : c.contexts.keySet().iterator().next();
            }
            c.save();
            out().println("contexte supprimé : " + name);
        }
    }
}
