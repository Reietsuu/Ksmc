package io.ksmc.cli.commands;

import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import java.util.Map;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Parameters;

@Command(name = "completion", description = {"Script de complétion du shell.",
        "bash : source <(ksmc completion bash)   zsh : autoload -U bashcompinit && bashcompinit && source <(ksmc completion zsh)",
        "fish : ksmc completion fish | source"})
public final class CompletionCommand extends BaseCommand {

    @Parameters(paramLabel = "SHELL", description = "bash, zsh ou fish")
    String shell;

    @Override
    protected void run() {
        CommandLine root = spec.root().commandLine();
        switch (shell) {
            case "bash", "zsh" -> out().print(AutoComplete.bash("ksmc", root));
            case "fish" -> out().print(fish(root));
            default -> throw CliException.usage("shell non pris en charge : " + shell + " (bash, zsh, fish)");
        }
        out().flush();
    }

    /** Complétion fish : sous-commandes (selon la commande déjà tapée) et options. */
    static String fish(CommandLine root) {
        StringBuilder sb = new StringBuilder("# Complétion fish de ksmc\ncomplete -c ksmc -f\n");
        fish(sb, root.getCommandSpec(), "");
        return sb.toString();
    }

    private static void fish(StringBuilder sb, CommandSpec spec, String path) {
        String condition = path.isEmpty() ? "__fish_use_subcommand"
                : "__fish_seen_subcommand_from " + path.substring(path.lastIndexOf(' ') + 1);
        for (Map.Entry<String, CommandLine> e : spec.subcommands().entrySet()) {
            CommandSpec sub = e.getValue().getCommandSpec();
            if (!sub.name().equals(e.getKey())) {
                continue; // alias
            }
            String desc = sub.usageMessage().description().length == 0 ? "" : sub.usageMessage().description()[0];
            sb.append("complete -c ksmc -n '").append(condition).append("' -a ").append(sub.name())
                    .append(" -d '").append(desc.replace("'", "\\'")).append("'\n");
            fish(sb, sub, path + " " + sub.name());
        }
        for (OptionSpec o : spec.options()) {
            for (String n : o.names()) {
                if (n.startsWith("--")) {
                    sb.append("complete -c ksmc -n '").append(condition).append("' -l ").append(n.substring(2)).append('\n');
                }
            }
        }
    }
}
