package io.ksmc.cli.commands;

import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.Output;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "user", description = "Comptes et rôles (ksmc.account.admin)", subcommands = {UserCommand.ListCmd.class,
        UserCommand.Create.class, UserCommand.Delete.class, UserCommand.Roles.class, UserCommand.Passwd.class,
        UserCommand.RoleList.class, UserCommand.RoleSet.class, UserCommand.Me.class})
public final class UserCommand extends BaseCommand {

    @Override
    protected void run() {
        spec.commandLine().usage(out());
    }

    @Command(name = "list", aliases = "ls", description = "Lister les comptes")
    public static final class ListCmd extends BaseCommand {
        @Mixin
        Output output;

        @Override
        protected void run() {
            output.list(out(), items(api().get("/api/v1/accounts")), new Output.Columns().name(n -> n.path("name").asText())
                    .col("NOM", "/name").col("RÔLES", "/roles").col("CHANGEMENT EXIGÉ", "/mustChangePassword"));
        }
    }

    @Command(name = "create", description = "Créer un compte (mot de passe à changer à la première connexion)")
    public static final class Create extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;
        @Option(names = {"-r", "--role"}, required = true, description = "Rôle (répétable)")
        List<String> roles;
        @Option(names = {"-p", "--password"}, description = "Mot de passe initial (demandé s'il manque)")
        String password;
        @Option(names = "--no-password-change", description = "Ne pas exiger de changement à la première connexion")
        boolean noChange;

        @Override
        protected void run() {
            String pw = password != null ? password : LoginCommand.prompt("Mot de passe initial de " + name, true);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("password", pw);
            body.put("roles", roles);
            body.put("mustChangePassword", !noChange);
            api().post("/api/v1/accounts", body);
            out().println("compte " + name + " créé");
        }
    }

    @Command(name = "delete", aliases = "rm", description = "Supprimer un compte (et ses jetons)")
    public static final class Delete extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;

        @Override
        protected void run() {
            api().delete("/api/v1/accounts/" + Api.encode(name));
            out().println("compte " + name + " supprimé");
        }
    }

    @Command(name = "roles", description = "Remplacer les rôles d'un compte")
    public static final class Roles extends BaseCommand {
        @Parameters(index = "0", paramLabel = "NOM")
        String name;
        @Parameters(index = "1..*", paramLabel = "RÔLE", arity = "1..*")
        List<String> roles;

        @Override
        protected void run() {
            api().put("/api/v1/accounts/" + Api.encode(name) + "/roles", Map.of("roles", roles));
            out().println("rôles de " + name + " : " + String.join(", ", roles));
        }
    }

    @Command(name = "passwd", description = "Réinitialiser le mot de passe d'un compte (changement exigé ensuite)")
    public static final class Passwd extends BaseCommand {
        @Parameters(paramLabel = "NOM")
        String name;
        @Option(names = {"-p", "--password"})
        String password;

        @Override
        protected void run() {
            String pw = password != null ? password : LoginCommand.prompt("Nouveau mot de passe de " + name, true);
            api().put("/api/v1/accounts/" + Api.encode(name) + "/password", Map.of("password", pw));
            out().println("mot de passe de " + name + " réinitialisé");
        }
    }

    @Command(name = "role-list", description = "Lister les rôles et leurs permissions")
    public static final class RoleList extends BaseCommand {
        @Override
        protected void run() {
            api().get("/api/v1/roles").properties().forEach(e -> out().println(e.getKey() + " : "
                    + Output.textOf(e.getValue())));
        }
    }

    @Command(name = "role-set", description = "Créer ou remplacer un rôle, ex. role-set support ksmc.service.* ksmc.task.read")
    public static final class RoleSet extends BaseCommand {
        @Parameters(index = "0", paramLabel = "RÔLE")
        String role;
        @Parameters(index = "1..*", paramLabel = "PERMISSION", arity = "1..*")
        List<String> permissions;

        @Override
        protected void run() {
            api().put("/api/v1/roles/" + Api.encode(role), Map.of("permissions", permissions));
            out().println("rôle " + role + " : " + String.join(", ", permissions));
        }
    }

    @Command(name = "me", description = "Utilisateur courant et permissions")
    public static final class Me extends BaseCommand {
        @Override
        protected void run() {
            var me = api().get("/api/v1/auth/me");
            out().println(me.path("name").asText() + " : " + Output.textOf(me.path("permissions")));
        }
    }
}
