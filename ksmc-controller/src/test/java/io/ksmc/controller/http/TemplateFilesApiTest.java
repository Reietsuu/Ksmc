package io.ksmc.controller.http;

import static io.ksmc.controller.http.ApiTestServer.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class TemplateFilesApiTest {
    KubernetesClient client;
    @TempDir
    Path tmp;
    FileArtifactStorage storage;
    ApiTestServer api;

    @BeforeEach
    void setUp() throws Exception {
        storage = new FileArtifactStorage(tmp);
        AuditLog audit = new AuditLog(null, "test", java.time.Clock.systemUTC(), List::of);
        api = new ApiTestServer(client, List.of(new TemplateFilesApi(storage, audit)), audit);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    String read(String key) throws Exception {
        try (var in = storage.open(key).orElseThrow()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    HttpResponse<byte[]> bytes(String method, String path, String token, byte[] body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.server.port() + path))
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void filesOfANestedTemplateCanBeWrittenReadRenamedAndDeleted() throws Exception {
        HttpResponse<String> put = api.call("PUT", "/api/v1/templates/lobby/default/files/server.properties", api.operator,
                "motd=Bienvenue");
        assertThat(put.statusCode()).isEqualTo(201);
        assertThat(read("templates/lobby/default/server.properties")).isEqualTo("motd=Bienvenue");
        assertThat(api.call("PUT", "/api/v1/templates/lobby/default/files/server.properties", api.operator, "motd=2")
                .statusCode()).isEqualTo(204);
        api.call("PUT", "/api/v1/templates/lobby/default/files/plugins/config.yml", api.operator, "a: 1");

        HttpResponse<String> list = api.call("GET", "/api/v1/templates/lobby/default/files", api.viewer, null);
        assertThat(json(list).get("files").findValuesAsText("path"))
                .containsExactlyInAnyOrder("server.properties", "plugins/config.yml");
        assertThat(api.call("GET", "/api/v1/templates/lobby/default/files/server.properties", api.viewer, null).body())
                .isEqualTo("motd=2");

        HttpResponse<String> renamed = api.call("PATCH", "/api/v1/templates/lobby/default/files/plugins/config.yml",
                api.operator, Map.of("path", "plugins/hello/config.yml"));
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(storage.exists("templates/lobby/default/plugins/hello/config.yml")).isTrue();
        assertThat(storage.exists("templates/lobby/default/plugins/config.yml")).isFalse();

        assertThat(api.call("DELETE", "/api/v1/templates/lobby/default/files/plugins", api.operator, null).statusCode())
                .isEqualTo(204);
        assertThat(storage.list("templates/lobby/default/")).containsExactly("templates/lobby/default/server.properties");
        assertThat(api.call("GET", "/api/v1/templates/lobby/default/files/absent", api.viewer, null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void writesNeedPermissionAndPathsCannotEscape() {
        assertThat(api.call("PUT", "/api/v1/templates/lobby/files/a.txt", api.viewer, "x").statusCode()).isEqualTo(403);
        assertThat(api.call("PUT", "/api/v1/templates/lobby/files/../../secret", api.operator, "x").statusCode())
                .isIn(400, 404);
        assertThat(api.call("PUT", "/api/v1/templates/lobby/files/%2e%2e/x", api.operator, "x").statusCode())
                .isIn(400, 404);
        assertThat(storage.exists("secret")).isFalse();
    }

    @Test
    void archivesCanBeDownloadedAndUploaded() throws Exception {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            out.putNextEntry(new ZipEntry("server.properties"));
            out.write("motd=Archive".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry("world/level.dat"));
            out.write(new byte[] {1, 2, 3});
        }
        storage.write("templates/game/old.txt", new ByteArrayInputStream("ancien".getBytes(StandardCharsets.UTF_8)));

        HttpResponse<byte[]> up = bytes("PUT", "/api/v1/templates/game/archive", api.operator, zip.toByteArray());
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(storage.exists("templates/game/old.txt")).as("remplacé").isFalse();
        assertThat(read("templates/game/server.properties")).isEqualTo("motd=Archive");

        bytes("PUT", "/api/v1/templates/game/archive?mode=merge", api.operator, zip.toByteArray());
        HttpResponse<byte[]> down = bytes("GET", "/api/v1/templates/game/archive", api.viewer, null);
        assertThat(down.statusCode()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(down.body()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("server.properties", "world/level.dat");
        assertThat(bytes("PUT", "/api/v1/templates/game/archive", api.operator, "pas un zip".getBytes()).statusCode())
                .isEqualTo(400);
        assertThat(api.call("GET", "/api/v1/templates/vide/archive", api.viewer, null).statusCode()).isEqualTo(404);
    }
}
