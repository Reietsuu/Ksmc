package io.ksmc.controller.storage;

import io.ksmc.api.spi.ArtifactStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link ArtifactStorage} sur un dossier local (PVC {@code ksmc-data} en production). */
public final class FileArtifactStorage implements ArtifactStorage {
    private static final String TMP_SUFFIX = ".ksmc-tmp";

    private final Path root;

    public FileArtifactStorage(Path root) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
    }

    private Path resolve(String key) {
        String k = ArtifactStorage.normalize(key);
        Path p = root.resolve(k).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid key: " + key);
        }
        return p;
    }

    @Override
    public Optional<InputStream> open(String key) throws IOException {
        Path p = resolve(key);
        try {
            return Files.isRegularFile(p) ? Optional.of(Files.newInputStream(p)) : Optional.empty();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void write(String key, InputStream content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName(), TMP_SUFFIX);
        try {
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public long size(String key) throws IOException {
        Path p = resolve(key);
        return Files.isRegularFile(p) ? Files.size(p) : -1;
    }

    @Override
    public long lastModified(String key) throws IOException {
        Path p = resolve(key);
        return Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : -1;
    }

    @Override
    public void delete(String keyOrPrefix) throws IOException {
        Path p = resolve(keyOrPrefix);
        if (Files.isDirectory(p)) {
            try (Stream<Path> walk = Files.walk(p)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(f);
                }
            }
        } else {
            Files.deleteIfExists(p);
        }
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        Path base = prefix == null || prefix.isBlank() ? root : resolve(prefix);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(TMP_SUFFIX))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
