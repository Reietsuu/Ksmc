package io.ksmc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Commandes task, template, service, player, module, user, token, events contre un faux controller. */
class CommandsTest extends CliTestSupport {

    @BeforeEach
    void login() throws Exception {
        loggedIn();
    }

    @Test
    void taskStartPrintsTheNewServices() {
        api.on("POST /api/v1/tasks/game/start", 202, "{\"services\":[\"game-3\",\"game-4\"],\"requested\":2,\"started\":2}");

        Result r = run("task", "start", "game", "-n", "2");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("game-3\ngame-4\n");
        assertThat(api.last("POST").text()).isEqualTo("{\"count\":2}");
    }

    @Test
    void taskCreateMaintenanceAndDelete() {
        api.on("POST /api/v1/tasks", 201, "{}").on("POST /api/v1/tasks/lobby/maintenance", 200, "{}")
                .on("DELETE /api/v1/tasks/lobby", 204, "");
        assertThat(run("task", "create", "lobby", "-s", "paper:1.21.4", "--min", "2", "--max", "5").code()).isZero();
        assertThat(api.last("POST").text()).contains("\"name\":\"lobby\"", "\"name\":\"paper\"", "\"version\":\"1.21.4\"",
                "\"minServiceCount\":2", "\"maxServiceCount\":5");
        assertThat(run("task", "maintenance", "lobby", "on").code()).isZero();
        assertThat(api.last("POST").text()).isEqualTo("{\"enabled\":true}");
        assertThat(run("task", "maintenance", "lobby", "peut-être").code()).isEqualTo(1);
        assertThat(run("task", "delete", "lobby").out()).contains("supprimé");
    }

    @Test
    void applyCreatesThenReplacesExistingResources() throws Exception {
        Path file = home.resolve("tasks.yaml");
        Files.writeString(file, """
                metadata: {name: lobby}
                spec: {environment: server, software: {name: paper}}
                status: {ready: "9/9"}
                ---
                metadata: {name: game}
                spec: {environment: server, software: {name: paper}}
                """);
        api.on("POST /api/v1/tasks", 201, "{}");
        Result created = run("task", "apply", "-f", file.toString());
        assertThat(created.out()).contains("tasks/lobby créé", "tasks/game créé");
        assertThat(api.requests.get(0).text()).doesNotContain("status");

        api.on("POST /api/v1/tasks", 409, "{\"code\":\"Conflict\",\"message\":\"existe déjà\"}")
                .on("PUT /api/v1/tasks/lobby", 200, "{}").on("PUT /api/v1/tasks/game", 200, "{}");
        assertThat(run("task", "apply", "-f", file.toString()).out()).contains("tasks/lobby mis à jour");
    }

    @Test
    void getShowsYamlByDefault() {
        api.on("GET /api/v1/tasks/lobby", 200, "{\"metadata\":{\"name\":\"lobby\"},\"spec\":{\"minServiceCount\":2}}");
        assertThat(run("task", "get", "lobby").out()).contains("name: \"lobby\"", "minServiceCount: 2");
        assertThat(run("task", "get", "lobby", "-o", "name").out()).isEqualTo("lobby\n");
    }

    @Test
    void serviceActions() {
        api.on("POST /api/v1/services/lobby-1/stop", 202, "{}").on("POST /api/v1/services/lobby-1/command", 202, "{}")
                .on("POST /api/v1/services/lobby-1/deploy", 202, "{}").on("POST /api/v1/services/lobby-1/restart", 202, "{}");
        assertThat(run("service", "stop", "lobby-1").code()).isZero();
        assertThat(run("service", "cmd", "lobby-1", "say", "Bonjour", "à", "tous").code()).isZero();
        assertThat(api.last("POST").text()).isEqualTo("{\"command\":\"say Bonjour à tous\"}");
        assertThat(run("service", "deploy", "lobby-1", "lobby/backup").code()).isZero();
        assertThat(api.last("POST").text()).isEqualTo("{\"template\":\"lobby/backup\"}");
        assertThat(run("service", "restart", "lobby-1").code()).isZero();
    }

    @Test
    void templateFiles() throws Exception {
        api.on("GET /api/v1/templates/lobby/default/files", 200,
                "{\"files\":[{\"path\":\"server.properties\",\"size\":12,\"modified\":\"x\"}]}");
        api.routes.put("GET /api/v1/templates/lobby/default/files/server.properties",
                new FakeApi.Response(200, "application/octet-stream", "motd=Salut\n".getBytes(StandardCharsets.UTF_8)));
        api.on("PUT /api/v1/templates/lobby/default/files/plugins/a.yml", 201, "")
                .on("PUT /api/v1/templates/lobby/default/archive", 200, "{\"files\":2}");

        assertThat(run("template", "ls", "lobby/default", "-o", "name").out()).isEqualTo("server.properties\n");
        assertThat(run("template", "cat", "lobby/default", "server.properties").out()).isEqualTo("motd=Salut\n");

        Path local = Files.writeString(home.resolve("a.yml"), "a: 1");
        assertThat(run("template", "put", "lobby/default", "plugins/a.yml", local.toString()).code()).isZero();
        assertThat(api.last("PUT").text()).isEqualTo("a: 1");

        Path dir = Files.createDirectories(home.resolve("tpl/plugins"));
        Files.writeString(dir.resolve("b.yml"), "b: 2");
        Files.writeString(home.resolve("tpl/server.properties"), "motd=x");
        assertThat(run("template", "push", "lobby/default", home.resolve("tpl").toString()).out()).contains("2 fichier");
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(api.last("PUT").body()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entries.add(e.getName());
            }
        }
        assertThat(entries).containsExactly("plugins/b.yml", "server.properties");
    }

    @Test
    void templatePullExtractsAndRefusesEscapingEntries() throws Exception {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            out.putNextEntry(new ZipEntry("world/level.dat"));
            out.write(new byte[] {1, 2});
        }
        api.routes.put("GET /api/v1/templates/game/archive", new FakeApi.Response(200, "application/zip", zip.toByteArray()));
        assertThat(run("template", "pull", "game", home.resolve("out").toString()).code()).isZero();
        assertThat(home.resolve("out/world/level.dat")).exists();

        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(evil)) {
            out.putNextEntry(new ZipEntry("../../evil.txt"));
            out.write(1);
        }
        api.routes.put("GET /api/v1/templates/evil/archive", new FakeApi.Response(200, "application/zip", evil.toByteArray()));
        assertThat(run("template", "pull", "evil", home.resolve("out2").toString()).code()).isEqualTo(2);
        assertThat(home.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void players() {
        api.on("GET /api/v1/players", 200, "{\"items\":[{\"name\":\"Steve\",\"uuid\":\"u1\",\"proxy\":\"p-1\",\"server\":\"lobby-1\"}]}")
                .on("POST /api/v1/players/Steve/send", 200, "{}").on("POST /api/v1/players/Steve/kick", 200, "{}")
                .on("POST /api/v1/players/Steve/message", 200, "{}");
        assertThat(run("player", "list", "-o", "name").out()).isEqualTo("Steve\n");
        assertThat(run("player", "send", "Steve", "--task", "lobby").code()).isZero();
        assertThat(api.last("POST").text()).isEqualTo("{\"task\":\"lobby\"}");
        assertThat(run("player", "send", "Steve").code()).isEqualTo(1);
        assertThat(run("player", "kick", "Steve", "triche", "détectée").code()).isZero();
        assertThat(api.last("POST").text()).isEqualTo("{\"reason\":\"triche détectée\"}");
        assertThat(run("player", "msg", "Steve", "Salut").code()).isZero();
    }

    @Test
    void modules() {
        api.on("POST /api/v1/modules", 201, "{}").on("PATCH /api/v1/modules/hello", 200, "{\"status\":{\"phase\":\"Enabled\"}}")
                .on("DELETE /api/v1/modules/hello", 204, "");
        assertThat(run("module", "install", "hello", "--store", "modules/hello.jar", "-c", "greeting=Bonjour",
                "-c", "limits.max=3").code()).isZero();
        assertThat(api.last("POST").text()).contains("\"store\":\"modules/hello.jar\"", "\"greeting\":\"Bonjour\"",
                "\"limits\":{\"max\":3}");
        assertThat(run("module", "install", "x", "--url", "https://e/x.jar").code()).isEqualTo(1);
        assertThat(run("module", "install", "x", "--store", "a", "--builtin", "b").code()).isEqualTo(1);

        assertThat(run("module", "config", "hello", "greeting=Salut").code()).isZero();
        assertThat(api.last("PATCH").text()).isEqualTo("{\"spec\":{\"config\":{\"greeting\":\"Salut\"}}}");
        assertThat(run("module", "disable", "hello").out()).contains("désactivé");
        assertThat(api.last("PATCH").text()).isEqualTo("{\"spec\":{\"enabled\":false}}");
        assertThat(run("module", "remove", "hello").code()).isZero();
    }

    @Test
    void usersAndTokens() {
        api.on("POST /api/v1/accounts", 201, "{}").on("PUT /api/v1/accounts/bob/roles", 204, "")
                .on("POST /api/v1/auth/tokens", 201, "{\"id\":\"abc\",\"token\":\"ksmc_abc_secret\"}")
                .on("GET /api/v1/auth/tokens", 200, "[{\"id\":\"abc\",\"name\":\"ci\",\"user\":\"admin\"}]")
                .on("DELETE /api/v1/auth/tokens/abc", 204, "");
        assertThat(run("user", "create", "bob", "-r", "operator", "-p", "bob-password-1").code()).isZero();
        assertThat(api.last("POST").text()).contains("\"roles\":[\"operator\"]", "\"mustChangePassword\":true");
        assertThat(run("user", "roles", "bob", "viewer", "support").code()).isZero();

        Result created = run("token", "create", "ci", "--days", "30");
        assertThat(created.out()).isEqualTo("ksmc_abc_secret\n");
        assertThat(created.err()).contains("ne sera plus affiché");
        assertThat(run("token", "list", "-o", "name").out()).isEqualTo("abc\n");
        assertThat(run("token", "revoke", "abc").code()).isZero();
    }

    @Test
    void eventsAreStreamed() {
        String sse = """
                : connected

                event: service.started
                id: 7
                data: {"type":"service.started","subject":"lobby-3","task":"lobby","timestamp":1792411537427}

                event: service.ready
                id: 8
                data: {"type":"service.ready","subject":"lobby-3"}

                """;
        api.routes.put("GET /api/v1/events/stream?type=service.*",
                new FakeApi.Response(200, "text/event-stream", sse.getBytes(StandardCharsets.UTF_8)));

        Result r = run("events", "-t", "service.*");
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("service.started", "lobby-3  (lobby)", "service.ready");
        assertThat(run("events", "-t", "service.*", "-o", "json").out().lines()).hasSize(2);
        assertThat(api.last("GET").headers().get("Accept")).contains("text/event-stream");
    }
}
