package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "console", description = {"Console interactive d'un service : logs en direct, chaque ligne tapée est",
        "envoyée comme commande. Ctrl+D pour quitter."})
public final class ConsoleCommand extends BaseCommand {

    @Parameters(paramLabel = "SERVICE")
    String name;
    @Option(names = {"-n", "--lines"}, description = "Lignes d'historique (défaut 200)", defaultValue = "200")
    int lines;

    @Override
    protected void run() throws Exception {
        session(api(), name, lines, true, out(), err());
    }

    /** Ouvre la console WebSocket ; {@code interactive} : saisie des commandes (JLine) jusqu'à Ctrl+D. */
    static void session(Api api, String service, int lines, boolean interactive, PrintWriter out, PrintWriter err)
            throws Exception {
        String ws = api.server().replaceFirst("^http", "ws") + "/api/v1/services/" + Api.encode(service)
                + "/console?lines=" + lines + (api.token() == null ? "" : "&access_token=" + Api.encode(api.token()));
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Terminal terminal = interactive ? TerminalBuilder.builder().system(true).dumb(true).build() : null;
        LineReader reader = terminal == null ? null : LineReaderBuilder.builder().terminal(terminal).build();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    print(partial.toString());
                    partial.setLength(0);
                }
                socket.request(1);
                return null;
            }

            private void print(String message) {
                String text;
                try {
                    JsonNode m = Api.JSON.readTree(message);
                    text = switch (m.path("type").asText()) {
                        case "log" -> m.path("line").asText();
                        case "command" -> "» " + m.path("command").asText()
                                + ("ok".equals(m.path("result").asText()) ? "" : " : " + m.path("result").asText());
                        case "error" -> "erreur : " + m.path("message").asText();
                        case "end" -> "— " + m.path("message").asText();
                        default -> message;
                    };
                } catch (Exception e) {
                    text = message;
                }
                if (reader != null) {
                    reader.printAbove(text);
                } else {
                    out.println(text);
                    out.flush();
                }
            }

            public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                closed.complete(null);
                return null;
            }

            public void onError(WebSocket socket, Throwable error) {
                closed.complete(null);
            }
        };
        WebSocket socket;
        try {
            socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(ws), listener)
                    .get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException h) {
                int code = h.getResponse().statusCode();
                throw CliException.api(switch (code) {
                    case 401 -> "Unauthorized : reconnectez-vous (ksmc login)";
                    case 403 -> "Forbidden : permission manquante (ksmc.service.console)";
                    case 404 -> "NotFound : service introuvable : " + service;
                    default -> "connexion à la console refusée (HTTP " + code + ")";
                });
            }
            throw CliException.api("console indisponible : " + e.getCause());
        }
        try {
            if (reader == null) {
                closed.get();
                return;
            }
            while (!closed.isDone()) {
                String line;
                try {
                    line = reader.readLine("> ");
                } catch (EndOfFileException | UserInterruptException e) {
                    break;
                }
                if (!line.isBlank()) {
                    socket.sendText(line, true).get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "fin").exceptionally(t -> null);
            if (terminal != null) {
                terminal.close();
            }
        }
    }
}
