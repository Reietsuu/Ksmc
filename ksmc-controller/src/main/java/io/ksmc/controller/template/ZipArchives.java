package io.ksmc.controller.template;

import io.ksmc.api.spi.ArtifactStorage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Outils de création/conversion d'archives ZIP (format échangé entre controller et wrapper). */
public final class ZipArchives {

    private ZipArchives() {
    }

    /** Archive tous les fichiers sous {@code prefix} du stockage, chemins relatifs au préfixe. */
    public static void fromStorage(ArtifactStorage storage, String prefix, OutputStream out) throws IOException {
        String base = ArtifactStorage.normalize(prefix);
        String root = base.endsWith("/") ? base : base + "/";
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String key : storage.list(base)) {
                zip.putNextEntry(new ZipEntry(key.substring(root.length())));
                try (InputStream in = storage.open(key).orElseThrow()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    /** Archive un dossier local (sans le dossier {@code .git}). */
    public static void fromDirectory(Path dir, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = dir.relativize(f);
                if (rel.startsWith(".git")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(rel.toString().replace('\\', '/')));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
    }

    /** Extrait une archive ZIP ou tar.gz (détection par signature) dans un dossier, en refusant les chemins sortants. */
    public static void extract(InputStream raw, Path dest) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        if (b0 == 0x1f && b1 == 0x8b) {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(in))) {
                TarArchiveEntry e;
                while ((e = tar.getNextEntry()) != null) {
                    if (e.isFile()) {
                        copyTo(tar, dest, e.getName());
                    }
                }
            }
        } else if (b0 == 'P' && b1 == 'K') {
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        copyTo(zip, dest, e.getName());
                    }
                }
            }
        } else {
            throw new IOException("unsupported archive format (expected zip or tar.gz)");
        }
    }

    private static void copyTo(InputStream in, Path dest, String name) throws IOException {
        Path target = dest.resolve(name).normalize();
        if (!target.startsWith(dest)) {
            throw new IOException("archive entry outside destination: " + name);
        }
        Files.createDirectories(target.getParent());
        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Supprime récursivement un dossier temporaire. */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
