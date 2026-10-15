package io.ksmc.api.spi;

import java.util.List;

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
     * Construction par un Job Kubernetes (BuildTools, installeurs). Le Job produit une archive ZIP
     * qu'il téléverse sur l'API interne ({@code PUT /internal/v1/artifacts/<cacheKey>}) ; elle est extraite
     * à la racine du service.
     */
    record BuildJob(int javaVersion, List<String> script, String cacheKey) implements ArtifactPlan {
    }
}
