package io.ksmc.api.spi;

import java.util.List;

/**
 * Résultat de résolution d'un logiciel.
 *
 * @param version     version effective (ex. {@code 1.21.4})
 * @param build       build effectif, ou {@code null}
 * @param plan        comment obtenir l'artefact
 * @param javaVersion version Java minimale imposée par la source, ou 0 si inconnue (règles de la définition)
 * @param launch      commande de lancement relative au dossier du service, sans la JVM
 *                    (ex. {@code ["-jar", "server.jar"]}) ; options JVM et mémoire sont ajoutées par le runtime
 */
public record ResolvedSoftware(String version, String build, ArtifactPlan plan, int javaVersion, List<String> launch) {

    /** Lancement standard d'un JAR exécutable déposé sous {@code server.jar}. */
    public static List<String> jarLaunch() {
        return List.of("-jar", "server.jar");
    }
}
