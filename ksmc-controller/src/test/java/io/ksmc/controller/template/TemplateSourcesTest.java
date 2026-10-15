package io.ksmc.controller.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.controller.storage.FileArtifactStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@EnableKubernetesMockClient(crud = true)
class TemplateSourcesTest {
    KubernetesClient client;

    @TempDir
    Path tmp;
    FileArtifactStorage storage;
    TemplateContext ctx;
    TemplateResolver resolver;
    HttpServer httpServer;
    final Map<String, byte[]> served = new TreeMap<>();

    @BeforeEach
    void setUp() throws IOException {
        storage = new FileArtifactStorage(tmp.resolve("data"));
        ctx = new TemplateContext("test", client, storage);
        resolver = new TemplateResolver(java.util.List.of(new StorageTemplateSource(), new ConfigMapTemplateSource(),
                new GitTemplateSource(), new HttpTemplateSource(HttpClient.newHttpClient())));
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", ex -> {
            byte[] body = served.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.getResponseHeaders().add("ETag", "\"" + body.length + "\"");
                ex.sendResponseHeaders(200, ex.getRequestMethod().equals("HEAD") ? -1 : body.length);
                if (!ex.getRequestMethod().equals("HEAD")) {
                    ex.getResponseBody().write(body);
                }
            }
            ex.close();
        });
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void storageTemplateIsArchivedAndCachedUntilChanged() throws IOException {
        storage.write("templates/lobby/default/server.properties", in("motd=Lobby"));
        storage.write("templates/lobby/default/plugins/a.jar", in("A"));
        ServiceTemplate tpl = template("lobby", "storage: {path: lobby/default}");

        TemplateResolver.Resolved first = resolver.resolve(tpl, ctx);
        assertThat(unzip(first.key())).containsOnlyKeys("server.properties", "plugins/a.jar")
                .containsEntry("server.properties", "motd=Lobby");
        assertThat(resolver.resolve(tpl, ctx)).isEqualTo(first);

        storage.write("templates/lobby/default/server.properties", in("motd=Changed!"));
        TemplateResolver.Resolved second = resolver.resolve(tpl, ctx);
        assertThat(second.digest()).isNotEqualTo(first.digest());
        assertThat(unzip(second.key())).containsEntry("server.properties", "motd=Changed!");
        assertThat(storage.exists(first.key())).as("ancienne archive nettoyée").isFalse();
    }

    @Test
    void missingStoragePathFails() {
        assertThatThrownBy(() -> resolver.resolve(template("x", "storage: {path: does/not/exist}"), ctx))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void configMapKeysBecomeFilesWithDoubleUnderscoreAsSeparator() throws IOException {
        client.configMaps().inNamespace("test").resource(new ConfigMapBuilder().withNewMetadata().withName("cfg")
                .endMetadata().addToData("server.properties", "pvp=false").addToData("plugins__Hello__config.yml", "a: 1")
                .build()).create();

        TemplateResolver.Resolved r = resolver.resolve(template("cm", "configMap: {name: cfg}"), ctx);

        assertThat(unzip(r.key())).containsEntry("server.properties", "pvp=false")
                .containsEntry("plugins/Hello/config.yml", "a: 1");
    }

    @Test
    void gitTemplateUsesSubdirectoryAtRef() throws Exception {
        Path repoDir = tmp.resolve("repo");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(repoDir.resolve("tpl/plugins"));
            Files.writeString(repoDir.resolve("tpl/plugins/Hello.jar"), "hello");
            Files.writeString(repoDir.resolve("README.md"), "ignored");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
        }
        ServiceTemplate tpl = template("g", "git: {url: '" + repoDir.toUri() + "', ref: main, path: tpl}");

        TemplateResolver.Resolved r = resolver.resolve(tpl, ctx);

        assertThat(unzip(r.key())).containsOnlyKeys("plugins/Hello.jar").containsEntry("plugins/Hello.jar", "hello");
    }

    @Test
    void httpTarGzAndZipArchivesAreSupported() throws IOException {
        served.put("/t.tar.gz", tarGz(Map.of("config/a.yml", "x: 1")));
        served.put("/t.zip", zip(Map.of("b.txt", "B")));

        assertThat(unzip(resolver.resolve(template("h1", "http: {url: '" + url("/t.tar.gz") + "'}"), ctx).key()))
                .containsEntry("config/a.yml", "x: 1");
        assertThat(unzip(resolver.resolve(template("h2", "http: {url: '" + url("/t.zip") + "'}"), ctx).key()))
                .containsEntry("b.txt", "B");
    }

    @Test
    void httpChecksumMismatchFails() throws IOException {
        served.put("/t.zip", zip(Map.of("b.txt", "B")));
        ServiceTemplate tpl = template("h", "http: {url: '" + url("/t.zip") + "', sha256: '" + "0".repeat(64) + "'}");

        assertThatThrownBy(() -> resolver.resolve(tpl, ctx)).isInstanceOf(IOException.class).hasMessageContaining("sha256");
    }

    @Test
    void httpNotFoundFails() {
        assertThatThrownBy(() -> resolver.resolve(template("h", "http: {url: '" + url("/missing.zip") + "'}"), ctx))
                .isInstanceOf(IOException.class).hasMessageContaining("404");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + path;
    }

    private static ServiceTemplate template(String name, String specYaml) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: ServiceTemplate
                metadata: {name: %s, namespace: test}
                spec: {%s}
                """.formatted(name, specYaml), ServiceTemplate.class);
    }

    private Map<String, String> unzip(String key) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (InputStream raw = storage.open(key).orElseThrow(); ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bos)) {
            for (var f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] tarGz(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new GZIPOutputStream(bos))) {
            for (var f : files.entrySet()) {
                byte[] data = f.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry e = new TarArchiveEntry(f.getKey());
                e.setSize(data.length);
                tar.putArchiveEntry(e);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }
}
