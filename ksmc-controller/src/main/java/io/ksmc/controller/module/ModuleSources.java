package io.ksmc.controller.module;

import io.ksmc.api.model.KsmcModuleSpec;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.controller.service.ArtifactCache;
import io.ksmc.controller.template.Digests;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Résout la source d'un {@code KsmcModule} en JAR local : URL HTTPS + sha256, coordonnées Maven (vérifiées par le
 * {@code .sha1} du dépôt), fichier du stockage du controller ({@code store}) ou module intégré à l'image.
 */
public final class ModuleSources {

    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";

    /** JAR prêt ; {@code digest} change quand le contenu change (déclenche un rechargement). */
    public sealed interface Outcome permits Ready, Pending {
    }

    public record Ready(Path jar, String digest) implements Outcome {
    }

    public record Pending(String message) implements Outcome {
    }

    private final ArtifactStorage storage;
    private final ArtifactCache cache;
    private final HttpClient http;
    private final Path workDir;
    private final Path builtinDir;
    private final Map<String, Boolean> verifiedMaven = new ConcurrentHashMap<>();

    /**
     * @param workDir    copies locales des JAR chargés (un classloader garde son JAR ouvert)
     * @param builtinDir JAR des modules intégrés ({@code <id>.jar})
     */
    public ModuleSources(ArtifactStorage storage, ArtifactCache cache, HttpClient http, Path workDir, Path builtinDir) {
        this.storage = storage;
        this.cache = cache;
        this.http = http;
        this.workDir = workDir;
        this.builtinDir = builtinDir;
    }

    public Outcome resolve(String moduleId, KsmcModuleSpec.Source source) throws ModuleException {
        if (source == null) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "source absente");
        }
        try {
            if (source.url != null) {
                return url(source.url, source.sha256);
            }
            if (source.maven != null) {
                return maven(source.maven, source.mavenRepository);
            }
            if (source.store != null) {
                return store(source.store);
            }
            if (source.builtin != null) {
                return builtin(source.builtin);
            }
        } catch (IOException e) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, e.getMessage(), e);
        }
        throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "aucune source définie pour " + moduleId);
    }

    private Outcome url(String url, String sha256) throws IOException, ModuleException {
        if (sha256 == null) {
            throw new ModuleException(ModuleException.CHECKSUM_MISMATCH, "sha256 obligatoire avec une URL");
        }
        String sha = sha256.toLowerCase(java.util.Locale.ROOT);
        ArtifactPlan.Download plan = new ArtifactPlan.Download(url, sha, null, "modules/sha256/" + sha + ".jar");
        Optional<Outcome> pending = download(plan);
        if (pending.isPresent()) {
            return pending.get();
        }
        return new Ready(materialize(ArtifactCache.storageKey(plan), "sha256-" + sha), "sha256:" + sha);
    }

    private Outcome maven(String coordinates, String repository) throws IOException, ModuleException {
        String[] gav = coordinates.split(":");
        if (gav.length != 3 || gav[0].isBlank() || gav[1].isBlank() || gav[2].isBlank()) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "coordonnées Maven attendues groupe:artefact:version");
        }
        String repo = repository == null || repository.isBlank() ? MAVEN_CENTRAL : repository.replaceAll("/+$", "");
        String url = repo + "/" + gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + gav[1] + "-" + gav[2]
                + ".jar";
        String id = Digests.sha256(url);
        ArtifactPlan.Download plan = new ArtifactPlan.Download(url, null, null, "modules/maven/" + id + ".jar");
        Optional<Outcome> pending = download(plan);
        if (pending.isPresent()) {
            return pending.get();
        }
        String key = ArtifactCache.storageKey(plan);
        if (!verifiedMaven.containsKey(key)) {
            String expected = fetchText(url + ".sha1").trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
            String actual;
            try (InputStream in = storage.open(key).orElseThrow(() -> new IOException("artefact absent : " + key))) {
                actual = digest(in, "SHA-1");
            }
            if (!expected.equals(actual)) {
                storage.delete(key);
                throw new ModuleException(ModuleException.CHECKSUM_MISMATCH,
                        "sha1 de " + url + " : attendu " + expected + ", obtenu " + actual);
            }
            verifiedMaven.put(key, Boolean.TRUE);
        }
        return new Ready(materialize(key, "maven-" + id), "maven:" + coordinates + "@" + repo);
    }

    private Outcome store(String key) throws IOException, ModuleException {
        if (key.contains("..") || key.startsWith("/")) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "chemin du stockage invalide : " + key);
        }
        if (!storage.exists(key)) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "absent du stockage : " + key);
        }
        String digest = Digests.sha256(key + "@" + storage.lastModified(key) + "#" + storage.size(key));
        return new Ready(materialize(key, "store-" + digest), "store:" + digest);
    }

    private Outcome builtin(String id) throws IOException, ModuleException {
        Path jar = builtinDir.resolve(id + ".jar");
        if (!id.matches("[a-z0-9-]+") || !Files.isRegularFile(jar)) {
            throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "module intégré inconnu : " + id);
        }
        return new Ready(jar, "builtin:" + id + "@" + Files.getLastModifiedTime(jar).toMillis());
    }

    /** {@code Optional.empty()} si l'artefact est en cache, sinon l'état en attente ; échec → exception. */
    private Optional<Outcome> download(ArtifactPlan.Download plan) throws IOException, ModuleException {
        ArtifactCache.Status status = cache.ensure(plan);
        return switch (status.state()) {
            case READY -> Optional.empty();
            case DOWNLOADING -> Optional.of(new Pending(status.message()));
            case FAILED -> throw new ModuleException(status.message() != null && status.message().contains("sha256 mismatch")
                    ? ModuleException.CHECKSUM_MISMATCH : ModuleException.DOWNLOAD_ERROR, status.message());
        };
    }

    /** Copie l'artefact du stockage dans le dossier de travail (une fois par contenu). */
    private Path materialize(String key, String name) throws IOException {
        Path target = workDir.resolve(name + ".jar");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(workDir);
        Path tmp = Files.createTempFile(workDir, name, ".part");
        try (InputStream in = storage.open(key).orElseThrow(() -> new IOException("artefact absent : " + key))) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private String fetchText(String url) throws IOException, ModuleException {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ModuleException(ModuleException.DOWNLOAD_ERROR, "HTTP " + resp.statusCode() + " pour " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrompu", e);
        }
    }

    private static String digest(InputStream in, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return Digests.hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
