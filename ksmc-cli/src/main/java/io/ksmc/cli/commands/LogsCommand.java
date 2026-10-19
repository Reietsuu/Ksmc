package io.ksmc.cli.commands;

import io.ksmc.cli.Api;
import io.ksmc.cli.ApiErrors;
import io.ksmc.cli.BaseCommand;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "logs", description = "Logs d'un service (-f : suivre en direct)")
public final class LogsCommand extends BaseCommand {

    @Parameters(paramLabel = "SERVICE")
    String name;
    @Option(names = {"-n", "--lines"}, description = "Nombre de lignes (défaut 200)", defaultValue = "200")
    int lines;
    @Option(names = {"-f", "--follow"}, description = "Suivre les nouvelles lignes (Ctrl+C pour arrêter)")
    boolean follow;
    @Option(names = {"-p", "--previous"}, description = "Démarrage précédent (tant que le Pod existe)")
    boolean previous;
    @Option(names = "--last-failure", description = "Extrait conservé du dernier échec")
    boolean lastFailure;

    @Override
    protected void run() throws Exception {
        String base = "/api/v1/services/" + Api.encode(name);
        if (lastFailure) {
            var last = api().get(base + "/lastlog");
            err().println("échec « " + last.path("reason").asText() + " » du " + last.path("capturedAt").asText());
            out().print(last.path("log").asText());
            out().flush();
            return;
        }
        if (follow) {
            ConsoleCommand.session(api(), name, lines, false, out(), err());
            return;
        }
        HttpResponse<InputStream> r = api().send("GET", base + "/logs?lines=" + lines + (previous ? "&previous=true" : ""),
                null, Map.of());
        try (InputStream in = r.body()) {
            byte[] bytes = in.readAllBytes();
            if (r.statusCode() >= 400) {
                throw ApiErrors.of(r.statusCode(), bytes);
            }
            out().print(new String(bytes, StandardCharsets.UTF_8));
            out().flush();
        }
    }
}
