package io.ksmc.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Base des tests : configuration isolée, faux controller, exécution capturée. */
abstract class CliTestSupport {

    @TempDir
    Path home;
    FakeApi api;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void startApi() throws Exception {
        System.setProperty("ksmc.config", home.resolve("config.yaml").toString());
        api = new FakeApi();
    }

    @AfterEach
    void stopApi() {
        api.close();
        System.clearProperty("ksmc.config");
    }

    Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Main.execute(new PrintWriter(out, true), new PrintWriter(err, true), args);
        return new Result(code, out.toString(), err.toString());
    }

    /** Contexte « test » déjà connecté au faux controller. */
    void loggedIn() throws Exception {
        Config c = new Config();
        Config.Context ctx = new Config.Context();
        ctx.server = api.url();
        ctx.user = "admin";
        ctx.token = "jeton-acces";
        ctx.refreshToken = "jeton-renouvellement";
        c.contexts.put("test", ctx);
        c.current = "test";
        c.save();
    }
}
