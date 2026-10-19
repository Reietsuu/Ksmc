package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.ApiErrors;
import io.ksmc.cli.BaseCommand;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "events", description = "Suivre les événements du cloud en direct (Ctrl+C pour arrêter)")
public final class EventsCommand extends BaseCommand {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Option(names = {"-t", "--type"}, description = "Type ou motif (service.*, player.login, > : tous)", defaultValue = ">")
    String type;
    @Option(names = "--task")
    String task;
    @Option(names = {"-e", "--environment"})
    String environment;
    @Option(names = "--since", description = "Rejouer depuis ce numéro de séquence")
    String since;
    @Option(names = {"-o", "--output"}, description = "text (défaut) ou json (une ligne par événement)", defaultValue = "text")
    String format;
    @Option(names = "--max", description = "S'arrêter après N événements", hidden = true)
    Integer max;

    @Override
    protected void run() throws Exception {
        String path = "/api/v1/events/stream" + Api.query(ResourceGroup.params("type", type, "task", task,
                "environment", environment, "since", since));
        HttpResponse<InputStream> r = api().send("GET", path, null, Map.of("Accept", "text/event-stream"));
        if (r.statusCode() >= 400) {
            throw ApiErrors.of(r.statusCode(), r.body().readAllBytes());
        }
        int seen = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (format.equals("json")) {
                    out().println(data);
                } else {
                    JsonNode e = Api.JSON.readTree(data);
                    String when = e.has("timestamp") ? TIME.format(Instant.ofEpochMilli(e.get("timestamp").asLong())) : "";
                    out().printf("%s  %-24s %s%s%n", when, e.path("type").asText(), e.path("subject").asText(),
                            e.path("task").isTextual() ? "  (" + e.path("task").asText() + ")" : "");
                }
                out().flush();
                if (max != null && ++seen >= max) {
                    return;
                }
            }
        }
    }
}
