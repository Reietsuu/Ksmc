package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.Config;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "password", description = "Changer son mot de passe (la session du contexte est renouvelée)")
public final class PasswordCommand extends BaseCommand {

    @Option(names = "--current", description = "Mot de passe actuel (demandé s'il manque)")
    String current;
    @Option(names = "--new", description = "Nouveau mot de passe (demandé s'il manque)")
    String next;

    @Override
    protected void run() throws Exception {
        String cur = current != null ? current : LoginCommand.prompt("Mot de passe actuel", true);
        String nw = next != null ? next : LoginCommand.prompt("Nouveau mot de passe", true);
        JsonNode session = api().post("/api/v1/auth/password", Map.of("currentPassword", cur, "newPassword", nw));
        Config config = main().config();
        String name = config.contextName(main().contextName());
        Config.Context ctx = name == null ? null : config.contexts.get(name);
        if (ctx != null && ctx.refreshToken != null) {
            ctx.token = session.path("accessToken").asText();
            ctx.refreshToken = session.path("refreshToken").asText();
            config.save();
        }
        out().println("mot de passe changé");
    }
}
