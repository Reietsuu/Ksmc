package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.Output;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "token", description = "Jetons d'API", subcommands = {TokenCommand.ListCmd.class, TokenCommand.Create.class,
        TokenCommand.Revoke.class})
public final class TokenCommand extends BaseCommand {

    @Override
    protected void run() {
        spec.commandLine().usage(out());
    }

    @Command(name = "list", aliases = "ls", description = "Lister ses jetons (--all : tous, administrateurs)")
    public static final class ListCmd extends BaseCommand {
        @Mixin
        Output output;
        @Option(names = "--all")
        boolean all;

        @Override
        protected void run() {
            output.list(out(), items(api().get("/api/v1/auth/tokens" + (all ? "?all=true" : ""))), new Output.Columns()
                    .name(n -> n.path("id").asText()).col("ID", "/id").col("NOM", "/name").col("UTILISATEUR", "/user")
                    .col("CRÉÉ", "/createdAt").col("EXPIRE", "/expiresAt"));
        }
    }

    @Command(name = "create", description = "Créer un jeton (affiché une seule fois)")
    public static final class Create extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;
        @Option(names = "--days", description = "Expiration en jours (défaut : jamais)")
        Integer days;

        @Override
        protected void run() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            if (days != null) {
                body.put("expiresInDays", days);
            }
            JsonNode r = api().post("/api/v1/auth/tokens", body);
            out().println(r.path("token").asText());
            err().println("jeton " + r.path("id").asText() + " créé : copiez-le, il ne sera plus affiché");
        }
    }

    @Command(name = "revoke", aliases = {"rm", "delete"}, description = "Révoquer un jeton")
    public static final class Revoke extends BaseCommand {
        @Parameters(paramLabel = "ID")
        String id;

        @Override
        protected void run() {
            api().delete("/api/v1/auth/tokens/" + Api.encode(id));
            out().println("jeton " + id + " révoqué");
        }
    }
}
