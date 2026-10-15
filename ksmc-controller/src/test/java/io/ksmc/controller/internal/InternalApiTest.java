package io.ksmc.controller.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.http.HttpServer;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient
class InternalApiTest {
    KubernetesMockServer server;
    KubernetesClient client;

    @TempDir
    Path tmp;
    FileArtifactStorage storage;
    HttpServer http;
    final HttpClient httpClient = HttpClient.newHttpClient();

    /** jeton → (serviceAccount, pod) */
    static final Map<String, String[]> TOKENS = Map.of(
            "svc-lobby-1", new String[] {"ksmc-service", "lobby-1-pod"},
            "builder", new String[] {"ksmc-builder", "build-pod"});

    @BeforeEach
    void setUp() throws Exception {
        server.expect().post().withPath("/apis/authentication.k8s.io/v1/tokenreviews").andReply(201, req -> {
            TokenReview in = Serialization.unmarshal(req.getBody().readUtf8(), TokenReview.class);
            String[] who = TOKENS.get(in.getSpec().getToken());
            TokenReviewBuilder out = new TokenReviewBuilder(in);
            if (who == null) {
                return out.withNewStatus().withAuthenticated(false).endStatus().build();
            }
            return out.withNewStatus().withAuthenticated(true).withNewUser()
                    .withUsername("system:serviceaccount:test:" + who[0])
                    .withExtra(Map.of("authentication.kubernetes.io/pod-name", List.of(who[1])))
                    .endUser().endStatus().build();
        }).always();
        server.expect().get().withPath("/api/v1/namespaces/test/pods/lobby-1-pod").andReturn(200, new PodBuilder()
                .withNewMetadata().withName("lobby-1-pod").addToLabels("ksmc.io/service", "lobby-1").endMetadata()
                .build()).always();

        storage = new FileArtifactStorage(tmp);
        storage.write("cache/templates/test/lobby/abc.zip", in("zipdata"));
        storage.write("cache/templates/other/secret/abc.zip", in("other"));
        storage.write("templates/lobby/raw.txt", in("raw"));
        storage.write("templates/build/world/old.dat", in("old"));
        ManifestProvider manifests = (ns, name) -> name.equals("lobby-1")
                ? Optional.of(Map.of("service", name)) : Optional.empty();
        InternalApi api = new InternalApi(new PodAuthenticator(client, Clock.systemUTC()), client, storage, manifests);
        http = new HttpServer(new HealthRegistry(), List.of(api)).start(0);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void manifestRequiresValidToken() throws Exception {
        assertThat(get("/internal/v1/services/test/lobby-1/manifest", null).statusCode()).isEqualTo(401);
        assertThat(get("/internal/v1/services/test/lobby-1/manifest", "forged").statusCode()).isEqualTo(401);
    }

    @Test
    void servicePodGetsItsOwnManifestOnly() throws Exception {
        HttpResponse<String> ok = get("/internal/v1/services/test/lobby-1/manifest", "svc-lobby-1");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("lobby-1");

        assertThat(get("/internal/v1/services/test/lobby-2/manifest", "svc-lobby-1").statusCode()).isEqualTo(403);
        assertThat(get("/internal/v1/services/other/lobby-1/manifest", "svc-lobby-1").statusCode()).isEqualTo(403);
    }

    @Test
    void downloadIsLimitedToCacheOfOwnNamespaceAndArtifacts() throws Exception {
        HttpResponse<String> ok = get("/internal/v1/files/cache/templates/test/lobby/abc.zip", "svc-lobby-1");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("zipdata");

        assertThat(get("/internal/v1/files/cache/templates/other/secret/abc.zip", "svc-lobby-1").statusCode()).isEqualTo(403);
        assertThat(get("/internal/v1/files/templates/lobby/raw.txt", "svc-lobby-1").statusCode()).isEqualTo(403);
        assertThat(get("/internal/v1/files/artifacts/missing.jar", "svc-lobby-1").statusCode()).isEqualTo(404);
    }

    @Test
    void onlyBuildersUploadArtifacts() throws Exception {
        assertThat(put("/internal/v1/artifacts/spigot/1.20.4.jar", "svc-lobby-1", "x".getBytes()).statusCode()).isEqualTo(403);
        assertThat(put("/internal/v1/artifacts/spigot/1.20.4.jar", "builder", "jar".getBytes()).statusCode()).isEqualTo(204);
        assertThat(storage.exists("artifacts/spigot/1.20.4.jar")).isTrue();
    }

    @Test
    void deployReplacesTemplateContent() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("world/level.dat"));
            zip.write("new".getBytes());
            zip.closeEntry();
        }

        HttpResponse<String> resp = put("/internal/v1/services/test/lobby-1/deploy?template=build/world", "svc-lobby-1",
                bos.toByteArray());

        assertThat(resp.statusCode()).isEqualTo(204);
        assertThat(storage.list("templates/build/world")).containsExactly("templates/build/world/world/level.dat");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return httpClient.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String token, byte[] body) throws Exception {
        return httpClient.send(request(path, token).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + http.port() + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
