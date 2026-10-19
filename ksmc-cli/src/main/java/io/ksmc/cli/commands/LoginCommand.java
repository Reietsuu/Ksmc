package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Config;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "login", description = {"Se connecter à un controller et enregistrer le contexte.",
        "Identifiants (demandés s'ils manquent) ou jeton d'API (--token)."})
public final class LoginCommand extends BaseCommand {

    @Parameters(paramLabel = "URL", description = "URL du controller, ex. http://localhost:8080")
    String url;
    @Option(names = {"-u", "--user"}, description = "Utilisateur (défaut : admin)", defaultValue = "admin")
    String user;
    @Option(names = {"-p", "--password"}, description = "Mot de passe (déconseillé : historique du shell)")
    String password;
    @Option(names = "--password-stdin", description = "Lire le mot de passe sur l'entrée standard")
    boolean passwordStdin;
    @Option(names = "--new-password", description = "Nouveau mot de passe si un changement est exigé")
    String newPassword;
    @Option(names = "--api-token", description = "Jeton d'API ksmc_... au lieu d'identifiants")
    String apiToken;
    @Option(names = {"-n", "--name"}, description = "Nom du contexte (défaut : hôte de l'URL)")
    String name;

    static String defaultName(String url) {
        URI u = URI.create(url);
        String host = u.getHost() == null ? url : u.getHost();
        return u.getPort() > 0 ? host + "-" + u.getPort() : host;
    }

    static String prompt(String label, boolean secret) {
        var console = System.console();
        if (console != null) {
            return secret ? new String(console.readPassword("%s : ", label)) : console.readLine("%s : ", label);
        }
        throw CliException.usage(label + " requis (option ou terminal interactif)");
    }

    @Override
    protected void run() throws Exception {
        String server = url.replaceAll("/+$", "");
        String ctxName = name != null ? name : defaultName(server);
        Config config = main().config();
        Config.Context ctx = new Config.Context();
        ctx.server = server;
        if (apiToken != null) {
            ctx.token = apiToken;
            JsonNode me = new Api(server, ctx, null).get("/api/v1/auth/me");
            ctx.user = me.path("name").asText();
        } else {
            String pw = password;
            if (pw == null && passwordStdin) {
                pw = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            if (pw == null) {
                pw = prompt("Mot de passe de " + user, true);
            }
            JsonNode session = new Api(server, null, null).post("/api/v1/auth/login",
                    Map.of("username", user, "password", pw));
            ctx.user = user;
            ctx.token = session.path("accessToken").asText();
            ctx.refreshToken = session.path("refreshToken").asText();
            if (session.path("mustChangePassword").asBoolean()) {
                String next = newPassword != null ? newPassword : System.console() != null
                        ? prompt("Changement obligatoire — nouveau mot de passe", true) : null;
                if (next == null) {
                    config.contexts.put(ctxName, ctx);
                    config.current = ctxName;
                    config.save();
                    throw CliException.usage("changement de mot de passe exigé : « ksmc password » ou login --new-password");
                }
                JsonNode renewed = new Api(server, ctx, null).post("/api/v1/auth/password",
                        Map.of("currentPassword", pw, "newPassword", next));
                ctx.token = renewed.path("accessToken").asText();
                ctx.refreshToken = renewed.path("refreshToken").asText();
                out().println("mot de passe changé");
            }
        }
        config.contexts.put(ctxName, ctx);
        config.current = ctxName;
        config.save();
        out().println("connecté à " + server + " en tant que " + ctx.user + " (contexte « " + ctxName + " »)");
    }
}
