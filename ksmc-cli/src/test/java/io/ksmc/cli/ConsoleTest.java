package io.ksmc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Console et logs -f contre un serveur WebSocket de test. */
class ConsoleTest extends CliTestSupport {

    Javalin ws;
    final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startWebSocket() throws Exception {
        ws = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.wsBeforeUpgrade("/api/v1/services/{name}/console", ctx -> {
                if (!"jeton-acces".equals(ctx.queryParam("access_token"))) {
                    throw new ForbiddenResponse();
                }
            });
            config.routes.ws("/api/v1/services/{name}/console", w -> {
                w.onConnect(c -> {
                    c.send("{\"type\":\"log\",\"line\":\"[12:00] Done (" + c.pathParam("name") + ")\"}");
                    if ("50".equals(c.queryParam("lines"))) {
                        c.send("{\"type\":\"log\",\"line\":\"[12:01] fin de l'historique\"}");
                        c.closeSession();
                    }
                });
                w.onMessage(c -> {
                    commands.add(c.message());
                    c.send("{\"type\":\"command\",\"command\":\"" + c.message() + "\",\"result\":\"ok\"}");
                    c.send("{\"type\":\"log\",\"line\":\"There are 0 of a max of 20 players online\"}");
                });
            });
        }).start(0);
        Config c = new Config();
        Config.Context ctx = new Config.Context();
        ctx.server = "http://127.0.0.1:" + ws.port();
        ctx.token = "jeton-acces";
        c.contexts.put("ws", ctx);
        c.current = "ws";
        c.save();
    }

    @AfterEach
    void stopWebSocket() {
        ws.stop();
    }

    @Test
    void logsFollowPrintsLinesUntilTheServerCloses() {
        Result r = run("service", "logs", "lobby-1", "-f", "-n", "50");
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("[12:00] Done (lobby-1)\n[12:01] fin de l'historique\n");
    }

    @Test
    void interactiveConsoleSendsTypedLinesAndShowsTheAnswer() throws Exception {
        InputStream stdin = System.in;
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // « list » puis fin de l'entrée (Ctrl+D) ; le petit délai laisse arriver la réponse.
        System.setIn(new SlowInput("list\n"));
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Result r = run("service", "console", "lobby-1");
            assertThat(r.code()).as(r.err()).isZero();
        } finally {
            System.setIn(stdin);
            System.setOut(stdout);
        }
        assertThat(commands).containsExactly("list");
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("[12:00] Done (lobby-1)", "players online");
    }

    @Test
    void refusedConsoleIsAnApiError() throws Exception {
        Config c = Config.load();
        c.contexts.get("ws").token = "mauvais";
        c.save();
        Result r = run("service", "console", "lobby-1");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Forbidden");
    }

    /** Entrée standard qui attend avant de signaler la fin (laisse le temps aux réponses d'arriver). */
    static final class SlowInput extends InputStream {
        private final ByteArrayInputStream data;
        private boolean waited;

        SlowInput(String text) {
            data = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() {
            int b = data.read();
            if (b < 0 && !waited) {
                waited = true;
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return b;
        }
    }
}
