package io.ksmc.api.spi;

import java.util.List;
import java.util.Map;

/** Comment obtenir l'artefact d'un logiciel. */
public sealed interface ArtifactPlan {

    /** Clé de cache stable de l'artefact ({@code artifacts/<cacheKey>}). */
    String cacheKey();

    /**
     * Téléchargement d'un fichier.
     *
     * @param target nom du fichier dans le dossier du service (ex. {@code server.jar})
     */
    record Download(String url, String sha256, String target, String cacheKey) implements ArtifactPlan {
    }

    /**
     * Construction par un Job Kubernetes (BuildTools, installeurs). Le wrapper du Job télécharge {@code downloads}
     * (URL → nom de fichier) dans un dossier de travail, y exécute {@code java <javaArgs>}, puis téléverse le contenu
     * de {@code outputDir} en ZIP sur l'API interne ({@code PUT /internal/v1/artifacts/<cacheKey>}).
     * Ce ZIP est ensuite extrait à la racine de chaque service.
     */
    record BuildJob(int javaVersion, Map<String, String> downloads, List<String> javaArgs, String outputDir,
            String cacheKey) implements ArtifactPlan {
    }
}
