package io.ksmc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

/** Socle : connexion, contextes, codes de sortie, formats, renouvellement de session. */
class CoreTest extends CliTestSupport {

    static final String SESSION = "{\"accessToken\":\"a1\",\"refreshToken\":\"r1\",\"mustChangePassword\":false}";

    @Test
    void loginStoresAContextReadableByTheUserOnly() throws Exception {
        api.on("POST /api/v1/auth/login", 200, SESSION);

        Result r = run("login", api.url(), "-u", "admin", "-p", "secret", "--name", "prod");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(api.last("POST").text()).contains("\"username\":\"admin\"", "\"password\":\"secret\"");
        Config c = Config.load();
        assertThat(c.current).isEqualTo("prod");
        assertThat(c.contexts.get("prod").token).isEqualTo("a1");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("config.yaml"))))
                .isEqualTo("rw-------");
    }

    @Test
    void forcedPasswordChangeDuringLogin() throws Exception {
        api.on("POST /api/v1/auth/login", 200, "{\"accessToken\":\"a0\",\"refreshToken\":\"r0\",\"mustChangePassword\":true}");
        api.on("POST /api/v1/auth/password", 200, SESSION);

        Result r = run("login", api.url(), "-p", "initial", "--new-password", "nouveau-mdp-123");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(api.last("POST").auth()).isEqualTo("Bearer a0");
        assertThat(Config.load().contexts.values().iterator().next().token).isEqualTo("a1");
    }

    @Test
    void contextUseSwitchesTheTargetCluster() throws Exception {
        try (FakeApi other = new FakeApi()) {
            api.on("POST /api/v1/auth/login", 200, SESSION).on("GET /api/v1/tasks", 200, "{\"items\":[]}");
            other.on("POST /api/v1/auth/login", 200, SESSION).on("GET /api/v1/tasks", 200, "{\"items\":[]}");
            run("login", api.url(), "-p", "x", "--name", "prod");
            run("login", other.url(), "-p", "x", "--name", "test");
            run("context", "use", "prod");

            run("task", "list");
            assertThat(api.requests).anyMatch(q -> q.uri().startsWith("/api/v1/tasks"));

            assertThat(run("context", "use", "test").code()).isZero();
            run("task", "list");
            assertThat(other.requests).anyMatch(q -> q.uri().startsWith("/api/v1/tasks"));
            assertThat(run("context", "current").out().trim()).isEqualTo("test");
            assertThat(run("context", "list").out()).contains("prod", "test");
            assertThat(run("context", "use", "absent").code()).isEqualTo(1);
        }
    }

    @Test
    void exitCodesAndErrorsOnStderr() throws Exception {
        loggedIn();
        api.on("DELETE /api/v1/services/lobby-1", 403,
                "{\"code\":\"Forbidden\",\"message\":\"permission manquante : ksmc.service.write\",\"details\":{}}");

        Result denied = run("service", "delete", "lobby-1");
        assertThat(denied.code()).isEqualTo(2);
        assertThat(denied.err()).contains("Forbidden", "ksmc.service.write");
        assertThat(denied.out()).isEmpty();

        assertThat(run("service", "list", "--option-inconnue").code()).isEqualTo(1);
        assertThat(run("task", "start").code()).isEqualTo(1);
        assertThat(run("--help").code()).isZero();
        assertThat(run("task", "--help").out()).contains("start", "maintenance", "apply");
    }

    @Test
    void notLoggedInIsAUsageError() {
        Result r = run("task", "list");
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("ksmc login");
    }

    @Test
    void outputFormats() throws Exception {
        loggedIn();
        api.on("GET /api/v1/services", 200, """
                {"items":[{"metadata":{"name":"lobby-1"},"spec":{"task":"lobby"},"status":{"phase":"Running","players":"3/20"}},
                          {"metadata":{"name":"lobby-2"},"spec":{"task":"lobby"},"status":{"phase":"Starting"}}],
                 "continue":null}""");

        Result names = run("service", "list", "-t", "lobby", "-o", "name");
        assertThat(names.out()).isEqualTo("lobby-1\nlobby-2\n");
        assertThat(api.last("GET").uri()).contains("task=lobby");

        String table = run("service", "list").out();
        assertThat(table.lines().findFirst().orElseThrow()).startsWith("NOM").contains("PHASE", "JOUEURS");
        assertThat(table).contains("lobby-1", "Running", "3/20");

        assertThat(Api.JSON.readTree(run("service", "list", "-o", "json").out()).size()).isEqualTo(2);
        assertThat(run("service", "list", "-o", "yaml").out()).contains("name: \"lobby-1\"").doesNotStartWith("---");
    }

    @Test
    void listFollowsCursorPagination() throws Exception {
        loggedIn();
        api.on("GET /api/v1/tasks?limit=500", 200, "{\"items\":[{\"metadata\":{\"name\":\"a\"}}],\"continue\":\"c1\"}");
        api.on("GET /api/v1/tasks?limit=500&continue=c1", 200, "{\"items\":[{\"metadata\":{\"name\":\"b\"}}],\"continue\":null}");

        assertThat(run("task", "list", "-o", "name").out()).isEqualTo("a\nb\n");
    }

    @Test
    void expiredSessionIsRenewedTransparently() throws Exception {
        loggedIn();
        api.on("GET /api/v1/auth/me", 401, "{\"code\":\"Unauthorized\",\"message\":\"expiré\"}");
        api.on("POST /api/v1/auth/refresh", 200, "{\"accessToken\":\"a2\",\"refreshToken\":\"r2\"}");

        run("user", "me");

        assertThat(api.requests).filteredOn(q -> q.uri().equals("/api/v1/auth/me")).hasSize(2)
                .last().satisfies(q -> assertThat(q.auth()).isEqualTo("Bearer a2"));
        assertThat(Config.load().contexts.get("test").refreshToken).isEqualTo("r2");
    }

    @Test
    void serverAndTokenOptionsOverrideTheContext() {
        api.on("GET /api/v1/tasks", 200, "{\"items\":[]}");
        assertThat(run("task", "list", "--server", api.url(), "--token", "ksmc_x_y").code()).isZero();
        assertThat(api.last("GET").auth()).isEqualTo("Bearer ksmc_x_y");
    }
}
