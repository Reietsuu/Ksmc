package io.ksmc.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Commandes des modules découvertes dynamiquement, cache, complétion. */
class ModuleCommandsTest extends CliTestSupport {

    static final String COMMANDS = """
            {"hello": [
              {"name": "greet", "description": "Saluer", "httpMethod": "POST", "path": "greet/{name}",
               "args": [{"name": "name", "description": "qui", "required": true},
                        {"name": "lang", "description": "langue", "required": false}]},
              {"name": "broadcast", "description": "Diffuser", "httpMethod": "POST", "path": "broadcast",
               "args": [{"name": "text", "required": true}]}
            ],
             "task": [{"name": "pirate", "httpMethod": "GET", "path": "x", "args": []}]}""";

    @BeforeEach
    void setUp() throws Exception {
        loggedIn();
        api.on("GET /api/v1/cli/commands", 200, COMMANDS);
    }

    long discoveries() {
        return api.requests.stream().filter(r -> r.uri().equals("/api/v1/cli/commands")).count();
    }

    @Test
    void moduleCommandCallsTheModuleRouteAndPrintsTheAnswer() {
        api.on("POST /api/v1/modules/hello/greet/Steve?lang=fr", 200, "{\"message\":\"Bonjour Steve\"}");

        Result r = run("hello", "greet", "Steve", "fr");

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"message\" : \"Bonjour Steve\"");
        assertThat(api.last("POST").auth()).isEqualTo("Bearer jeton-acces");
    }

    @Test
    void textAnswersAndMissingArguments() {
        api.routes.put("POST /api/v1/modules/hello/broadcast?text=Salut%20tout%20le%20monde",
                new FakeApi.Response(202, "text/plain", "envoyé".getBytes()));
        assertThat(run("hello", "broadcast", "Salut tout le monde").out()).isEqualTo("envoyé\n");
        Result missing = run("hello", "greet");
        assertThat(missing.code()).as(missing.err()).isEqualTo(1);
        assertThat(run("hello", "greet", "--help").out()).contains("Saluer", "NAME", "qui");
        assertThat(run("hello").out()).contains("greet", "broadcast");
    }

    @Test
    void builtinCommandsAreNeverShadowedAndDiscoveryIsCached() {
        api.on("GET /api/v1/tasks?limit=500", 200, "{\"items\":[]}");
        // Commande intégrée : pas de découverte.
        assertThat(run("task", "list").code()).isZero();
        assertThat(discoveries()).isZero();

        api.on("POST /api/v1/modules/hello/broadcast?text=a", 202, "{}");
        run("hello", "broadcast", "a");
        run("hello", "broadcast", "a");
        assertThat(discoveries()).isEqualTo(1);
        assertThat(home.resolve("cache")).isDirectory();
        // Le module « task » ne remplace pas la commande intégrée.
        assertThat(run("task", "pirate").code()).isEqualTo(1);
    }

    @Test
    void unreachableControllerDoesNotBreakTheCli() throws Exception {
        api.close();
        Result r = run("inconnu");
        assertThat(r.code()).isEqualTo(1);
        assertThat(run("--version").code()).isZero();
    }

    @Test
    void shellCompletion() throws Exception {
        String bash = run("completion", "bash").out();
        assertThat(bash).contains("complete -F", "ksmc").contains("hello");
        Path script = Files.writeString(home.resolve("ksmc-completion.bash"), bash);
        Process p = new ProcessBuilder("bash", "-n", script.toString()).redirectErrorStream(true).start();
        assertThat(p.waitFor()).as(new String(p.getInputStream().readAllBytes())).isZero();

        String fish = run("completion", "fish").out();
        assertThat(fish).contains("complete -c ksmc -n '__fish_use_subcommand' -a task",
                "__fish_seen_subcommand_from task' -a start");
        assertThat(run("completion", "powershell").code()).isEqualTo(1);
    }
}
