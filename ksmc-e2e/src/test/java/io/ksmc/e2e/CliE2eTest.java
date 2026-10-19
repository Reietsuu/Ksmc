package io.ksmc.e2e;

import static io.ksmc.e2e.E2eSupport.CLIENT;
import static io.ksmc.e2e.E2eSupport.EXAMPLES;
import static io.ksmc.e2e.E2eSupport.NAMESPACE;
import static io.ksmc.e2e.E2eSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.KsmcModuleStatus;
import io.ksmc.api.model.ServiceTask;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Scénario du change p09 : la CLI {@code ksmc} packagée, contre le cluster de test. */
class CliE2eTest {

    static final Path CLI = EXAMPLES.getParent().resolve("ksmc-cli/build/install/ksmc/bin/ksmc");
    static ApiClient api;
    static Path config;

    record Run(int code, String out, String err) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        assertThat(CLI).as("construire d'abord la CLI (./gradlew :ksmc-cli:installDist)").exists();
        api = new ApiClient();
        api.admin(); // mot de passe initial changé si nécessaire
        config = Files.createTempDirectory("ksmc-cli-e2e").resolve("config.yaml");
    }

    @AfterAll
    static void tearDown() throws Exception {
        CLIENT.resources(ServiceTask.class).inNamespace(NAMESPACE).withName("e2ec-game").delete();
        CLIENT.resources(KsmcModule.class).inNamespace(NAMESPACE).withName("hello").delete();
        api.close();
    }

    static Run ksmc(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(CLI.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("KSMC_CONFIG", config.toString());
        Path err = Files.createTempFile("ksmc-err", ".txt");
        pb.redirectError(err.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(90, TimeUnit.SECONDS)).as("ksmc " + String.join(" ", args)).isTrue();
        return new Run(p.exitValue(), out, Files.readString(err));
    }

    static Run ok(String... args) throws Exception {
        Run r = ksmc(args);
        assertThat(r.code()).as("ksmc " + String.join(" ", args) + "\n" + r.out() + r.err()).isZero();
        return r;
    }

    @Test
    void loginTaskStartServiceListAndModuleCommand() throws Exception {
        ok("login", api.base(), "-u", "admin", "-p", ApiClient.ADMIN_PASSWORD, "--name", "e2e");
        assertThat(ok("context", "current").out().trim()).isEqualTo("e2e");

        ok("task", "create", "e2ec-game", "-s", "velocity:latest", "-e", "proxy", "--min", "0", "--max", "3", "-m", "512");
        Run started = ok("task", "start", "e2ec-game", "-n", "1");
        assertThat(started.out().trim()).isEqualTo("e2ec-game-1");
        await("service créé par le reconciler", Duration.ofSeconds(60), () -> {
            try {
                return ksmc("service", "list", "-t", "e2ec-game", "-o", "name").out().equals("e2ec-game-1\n");
            } catch (Exception e) {
                return false;
            }
        });

        // Erreur d'API : code 2, message sur stderr.
        Run denied = ksmc("task", "get", "absente");
        assertThat(denied.code()).isEqualTo(2);
        assertThat(denied.err()).contains("NotFound");

        // Module hello installé par la CLI, puis sa commande découverte dynamiquement.
        ModuleSystemE2eTest.upload("/data/modules/hello.jar", ModuleSystemE2eTest.MODULE_JAR);
        ok("module", "install", "hello", "--store", "modules/hello.jar", "-c", "greeting=Salut");
        await("module hello actif", Duration.ofMinutes(2), () -> {
            KsmcModule m = CLIENT.resources(KsmcModule.class).inNamespace(NAMESPACE).withName("hello").get();
            return m != null && m.getStatus() != null && m.getStatus().phase == KsmcModuleStatus.Phase.Enabled;
        });
        assertThat(ok("module", "list", "-o", "name").out()).contains("hello");
        Run broadcast = ok("hello", "broadcast", "coucou-cli");
        assertThat(broadcast.out()).contains("coucou-cli");
        assertThat(ok("hello", "--help").out()).contains("broadcast");
        assertThat(ok("completion", "bash").out()).contains("complete -F");
    }
}
