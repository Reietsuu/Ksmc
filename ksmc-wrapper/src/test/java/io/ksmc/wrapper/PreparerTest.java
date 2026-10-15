package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.Wire;
import io.ksmc.wire.manifest.ServiceManifest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparerTest {
    @TempDir
    Path tmp;
    FakeControllerServer controller;
    WrapperEnv env;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FakeControllerServer();
        Path token = Files.writeString(tmp.resolve("token"), "tok");
        env = new WrapperEnv(controller.url(), token.toFile(), "test", "lobby-1", tmp.resolve("server").toFile(), 0, null);
        controller.responses.put("/files/server.jar", "JAR".getBytes());
        controller.responses.put("/files/group.zip", FakeControllerServer.zip(Map.of(
                "server.properties", "motd=Group\npvp=true\n", "plugins/A.jar", "A")));
        controller.responses.put("/files/task.zip", FakeControllerServer.zip(Map.of("server.properties", "motd=Task\npvp=false\n")));
    }

    @AfterEach
    void tearDown() {
        controller.close();
    }

    ServiceManifest manifest() {
        ServiceManifest m = new ServiceManifest();
        m.namespace = "test";
        m.service = "lobby-1";
        m.environment = "server";
        m.family = "paper";
        m.softwareVersion = "1.21.4";
        m.network.forwarding = "none";
        ServiceManifest.Artifact a = new ServiceManifest.Artifact();
        a.url = "/files/server.jar";
        a.target = "server.jar";
        m.artifacts.add(a);
        m.templates.add(template("group", "/files/group.zip", false));
        m.templates.add(template("task", "/files/task.zip", false));
        return m;
    }

    static ServiceManifest.Template template(String name, String url, boolean optional) {
        ServiceManifest.Template t = new ServiceManifest.Template();
        t.name = name;
        t.url = url;
        t.optional = optional;
        return t;
    }

    void serve(ServiceManifest m) {
        controller.responses.put("/internal/v1/services/test/lobby-1/manifest", Wire.toJson(m).getBytes());
    }

    String read(String rel) throws Exception {
        return Files.readString(env.serverDir.toPath().resolve(rel));
    }

    @Test
    void appliesSoftwareThenTemplatesInOrderThenNetworkConfig() throws Exception {
        serve(manifest());

        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isZero();

        assertThat(read("server.jar")).isEqualTo("JAR");
        assertThat(read("plugins/A.jar")).isEqualTo("A");
        // La task (appliquée après le groupe) gagne ; puis Ksmc impose la configuration réseau.
        assertThat(read("server.properties")).contains("motd=Task", "pvp=false", "server-port=25565", "online-mode=false");
        assertThat(env.manifestFile()).isFile();
        assertThat(controller.authHeaders.get("/internal/v1/services/test/lobby-1/manifest")).isEqualTo("Bearer tok");
        assertThat(new File(env.serverDir, "eula.txt")).doesNotExist();
    }

    @Test
    void missingRequiredTemplateFailsButOptionalIsSkipped() throws Exception {
        ServiceManifest m = manifest();
        m.templates.add(template("maybe", "/files/missing.zip", true));
        serve(m);
        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isZero();

        m.templates.add(template("required", "/files/missing.zip", false));
        serve(m);
        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isEqualTo(1);
    }

    @Test
    void checksumMismatchFails() throws Exception {
        ServiceManifest m = manifest();
        m.artifacts.get(0).sha256 = "0".repeat(64);
        serve(m);

        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isEqualTo(1);
    }

    @Test
    void staticServiceKeepsItsFilesOnRestart() throws Exception {
        ServiceManifest m = manifest();
        m.staticService = true;
        serve(m);
        new Preparer(env, new InternalClient(env)).prepare();
        Files.writeString(env.serverDir.toPath().resolve("server.properties"), "motd=Edited by players\n");
        Files.writeString(env.serverDir.toPath().resolve("world.dat"), "world");

        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isZero();

        assertThat(read("server.properties")).contains("motd=Edited by players");
        assertThat(read("world.dat")).isEqualTo("world");
    }

    @Test
    void localImageTemplatesAreCopied() throws Exception {
        Path image = Files.createDirectories(tmp.resolve("image-tpl/config"));
        Files.writeString(image.resolve("x.yml"), "x: 1");
        ServiceManifest m = manifest();
        ServiceManifest.Template t = new ServiceManifest.Template();
        t.name = "img";
        t.localPath = tmp.resolve("image-tpl").toString();
        m.templates.add(t);
        m.acceptEula = true;
        serve(m);

        assertThat(new Preparer(env, new InternalClient(env)).prepare()).isZero();
        assertThat(read("config/x.yml")).isEqualTo("x: 1");
        assertThat(read("eula.txt")).contains("eula=true");
    }

    @Test
    void globExcludes() {
        assertThat(IoUtil.globToRegex("logs/").matcher("logs/latest.log").matches()).isTrue();
        assertThat(IoUtil.globToRegex("*.log").matcher("a.log").matches()).isTrue();
        assertThat(IoUtil.globToRegex("*.log").matcher("logs/a.log").matches()).isFalse();
        assertThat(IoUtil.globToRegex("**/*.tmp").matcher("a/b/c.tmp").matches()).isTrue();
        assertThat(Collections.<String>emptyList()).isEmpty();
    }
}
