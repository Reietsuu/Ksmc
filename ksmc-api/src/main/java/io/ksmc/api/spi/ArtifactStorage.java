package io.ksmc.api.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Stockage des fichiers gérés par le controller : templates {@code storage}, artefacts logiciels en cache,
 * archives de templates résolus. Les clés sont des chemins relatifs séparés par {@code /}
 * (ex. {@code templates/lobby/default/server.properties}).
 *
 * <p>Implémentation par défaut : système de fichiers (PVC). Un module peut en fournir une autre (S3...).
 */
public interface ArtifactStorage {

    /** Ouvre le contenu d'une clé, vide si elle n'existe pas. L'appelant ferme le flux. */
    Optional<InputStream> open(String key) throws IOException;

    /** Écrit le contenu de façon atomique : un lecteur voit l'ancienne ou la nouvelle version, jamais un fichier partiel. */
    void write(String key, InputStream content) throws IOException;

    boolean exists(String key) throws IOException;

    /** Taille en octets, ou -1 si la clé n'existe pas. */
    long size(String key) throws IOException;

    /** Supprime une clé ou tout un préfixe ({@code a/b/} supprime tout ce qui est sous {@code a/b}). */
    void delete(String keyOrPrefix) throws IOException;

    /** Liste récursivement les clés sous un préfixe (vide = tout), triées. */
    List<String> list(String prefix) throws IOException;

    /** Vérifie et normalise une clé ; refuse les chemins absolus et les {@code ..}. */
    static String normalize(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("empty key");
        }
        String k = key.replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        for (String part : k.split("/")) {
            if (part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("invalid key: " + key);
            }
        }
        return k.replaceAll("/+", "/");
    }
}
