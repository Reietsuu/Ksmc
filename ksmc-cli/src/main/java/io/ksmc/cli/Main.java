package io.ksmc.cli;

import io.ksmc.cli.commands.CompletionCommand;
import io.ksmc.cli.commands.ContextCommand;
import io.ksmc.cli.commands.EventsCommand;
import io.ksmc.cli.commands.GroupCommand;
import io.ksmc.cli.commands.LoginCommand;
import io.ksmc.cli.commands.ModuleCommand;
import io.ksmc.cli.commands.PasswordCommand;
import io.ksmc.cli.commands.PlayerCommand;
import io.ksmc.cli.commands.ServiceCommand;
import io.ksmc.cli.commands.SoftwareCommand;
import io.ksmc.cli.commands.TaskCommand;
import io.ksmc.cli.commands.TemplateCommand;
import io.ksmc.cli.commands.TokenCommand;
import io.ksmc.cli.commands.UserCommand;
import java.io.IOException;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/** Point d'entrée de la CLI {@code ksmc}. Codes de sortie : 0 succès, 1 erreur d'usage, 2 erreur de l'API. */
@Command(name = "ksmc", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = "Gestion du cloud Minecraft Ksmc (API REST du controller).",
        subcommands = {LoginCommand.class, ContextCommand.class, PasswordCommand.class, TaskCommand.class,
                GroupCommand.class, TemplateCommand.class, ServiceCommand.class, PlayerCommand.class,
                SoftwareCommand.class, ModuleCommand.class, UserCommand.class, TokenCommand.class, EventsCommand.class,
                CompletionCommand.class})
public final class Main implements Runnable {

    @Option(names = "--context", scope = ScopeType.INHERIT, description = "Contexte à utiliser (défaut : courant)")
    String contextName;

    @Option(names = "--server", scope = ScopeType.INHERIT, description = "URL du controller (remplace le contexte)")
    String server;

    @Option(names = "--token", scope = ScopeType.INHERIT, description = "Jeton (remplace le contexte ; ou $KSMC_TOKEN)")
    String token;

    private Config config;
    private Api api;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public Config config() {
        if (config == null) {
            try {
                config = Config.load();
            } catch (IOException e) {
                throw CliException.usage("configuration illisible (" + Config.path() + ") : " + e.getMessage());
            }
        }
        return config;
    }

    public String contextName() {
        return contextName;
    }

    /** API du contexte, éventuellement remplacée par {@code --server}, {@code --token} ou {@code $KSMC_TOKEN}. */
    public Api api() {
        if (api != null) {
            return api;
        }
        String explicitToken = token != null ? token : System.getenv("KSMC_TOKEN");
        if (server != null) {
            Config.Context c = new Config.Context();
            c.server = server;
            c.token = explicitToken;
            api = new Api(server, c, null);
        } else {
            Config.Context c = config().context(contextName);
            if (explicitToken != null) {
                Config.Context copy = new Config.Context();
                copy.server = c.server;
                copy.token = explicitToken;
                c = copy;
            }
            api = new Api(c.server, c, explicitToken != null ? null : config());
        }
        return api;
    }

    /** Pour les tests : exécute avec des sorties capturées et renvoie le code de sortie. */
    public static int execute(PrintWriter out, PrintWriter err, String... args) {
        Main main = new Main();
        main.out = out;
        CommandLine cl = commandLine(main, args);
        cl.setOut(out);
        cl.setErr(err);
        return cl.execute(args);
    }

    static CommandLine commandLine(Main main, String[] args) {
        CommandLine cl = new CommandLine(main);
        cl.setExecutionExceptionHandler((e, cmd, parsed) -> {
            if (e instanceof CliException ce) {
                cmd.getErr().println("erreur : " + ce.getMessage());
                cmd.getErr().flush();
                return ce.exitCode();
            }
            cmd.getErr().println("erreur : " + e);
            cmd.getErr().flush();
            return 2;
        });
        cl.setParameterExceptionHandler((e, unmatched) -> {
            CommandLine cmd = e.getCommandLine();
            cmd.getErr().println("erreur : " + e.getMessage());
            cmd.getErr().println("voir « " + cmd.getCommandSpec().qualifiedName() + " --help »");
            cmd.getErr().flush();
            return 1;
        });
        // Options globales (--context, --server...) lues avant la découverte des commandes de modules.
        try {
            new CommandLine(new GlobalOptions(main)).setUnmatchedArgumentsAllowed(true).parseArgs(args);
        } catch (CommandLine.ParameterException ignored) {
            // erreurs signalées par l'analyse principale
        }
        ModuleCommands.register(cl, main, args);
        withHelp(cl);
        return cl;
    }

    /** {@code -h/--help} sur toutes les commandes (et sous-commandes ajoutées dynamiquement). */
    static void withHelp(CommandLine cl) {
        if (!cl.getCommandSpec().mixinStandardHelpOptions()) {
            cl.getCommandSpec().mixinStandardHelpOptions(true);
        }
        cl.getSubcommands().values().stream().distinct().forEach(Main::withHelp);
    }

    public static void main(String[] args) {
        System.exit(commandLine(new Main(), args).execute(args));
    }

    private PrintWriter out;

    /** Sortie standard (celle de l'exécution en cours). */
    public PrintWriter out() {
        if (out == null) {
            out = new PrintWriter(System.out, true);
        }
        return out;
    }

    /** Lecture anticipée des options globales, sans valider le reste de la ligne de commande. */
    @Command(name = "ksmc")
    static final class GlobalOptions {
        private final Main main;

        GlobalOptions(Main main) {
            this.main = main;
        }

        @Option(names = "--context")
        void context(String v) {
            main.contextName = v;
        }

        @Option(names = "--server")
        void server(String v) {
            main.server = v;
        }

        @Option(names = "--token")
        void token(String v) {
            main.token = v;
        }
    }

    static final class Version implements CommandLine.IVersionProvider {
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            return new String[] {"ksmc " + (v == null ? "dev" : v)};
        }
    }
}
