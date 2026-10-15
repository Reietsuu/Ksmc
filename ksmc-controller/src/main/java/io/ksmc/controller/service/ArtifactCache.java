package io.ksmc.controller.service;

import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.controller.template.Digests;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache des artefacts téléchargés ({@code artifacts/<cacheKey>}) partagé par tous les services du cluster.
 * Les téléchargements se font en arrière-plan ; une même clé n'est téléchargée qu'une fois à la fois.
 */
public final class ArtifactCache {
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactCache.class);

    public enum State { READY, DOWNLOADING, FAILED }

    public record Status(State state, String message) {
    }

    private final ArtifactStorage storage;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    public ArtifactCache(ArtifactStorage storage, HttpClient http) {
        this.storage = storage;
        this.http = http;
    }

    public static String storageKey(ArtifactPlan plan) {
        return "artifacts/" + plan.cacheKey();
    }

    /** État de l'artefact, en lançant le téléchargement s'il est absent. Un échec est réessayé à l'appel suivant. */
    public Status ensure(ArtifactPlan.Download plan) throws IOException {
        String key = storageKey(plan);
        if (storage.exists(key)) {
            return new Status(State.READY, null);
        }
        String failure = failures.remove(key);
        if (failure != null) {
            return new Status(State.FAILED, failure);
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) == null) {
            executor.submit(() -> download(plan, key));
        }
        return new Status(State.DOWNLOADING, "downloading " + plan.url());
    }

    private void download(ArtifactPlan.Download plan, String key) {
        Path tmp = null;
        try {
            LOG.info("Téléchargement de {} → {}", plan.url(), key);
            HttpRequest req = HttpRequest.newBuilder(URI.create(plan.url())).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "ksmc (+https://github.com/Reietsuu/Ksmc)").GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode() + " for " + plan.url());
            }
            tmp = Files.createTempFile("ksmc-artifact", ".part");
            MessageDigest md = Digests.sha256();
            try (InputStream in = new DigestInputStream(resp.body(), md)) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = Digests.hex(md.digest());
            if (plan.sha256() != null && !plan.sha256().equalsIgnoreCase(actual)) {
                throw new IOException("sha256 mismatch for " + plan.url() + ": expected " + plan.sha256() + ", got " + actual);
            }
            try (InputStream in = Files.newInputStream(tmp)) {
                storage.write(key, in);
            }
        } catch (Exception e) {
            LOG.warn("Échec du téléchargement de {} : {}", plan.url(), e.getMessage());
            failures.put(key, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            inFlight.remove(key);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // fichier temporaire
                }
            }
        }
    }
}
