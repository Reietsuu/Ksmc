package io.ksmc.api.spi;

import io.ksmc.api.model.ServiceTemplate;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Convertit une source de {@link ServiceTemplate} en archive ZIP de fichiers à appliquer au service.
 *
 * <p>Le controller met les archives en cache par {@link #digest} : une source inchangée n'est pas relue.
 * Les sources intégrées sont {@code storage}, {@code git}, {@code http} et {@code configMap} ; un module peut
 * en ajouter (ex. S3) en lisant ses paramètres dans {@code spec.extensions}.
 */
public interface TemplateSourceProvider {

    /** Identifiant de la source (ex. {@code git}). */
    String type();

    /** Vrai si ce fournisseur sait traiter ce template. */
    boolean supports(ServiceTemplate template);

    /** Empreinte peu coûteuse du contenu actuel (commit, ETag, resourceVersion...). Lève une exception si la source est indisponible. */
    String digest(ServiceTemplate template, TemplateContext context) throws IOException;

    /** Écrit le contenu de la source sous forme d'archive ZIP (chemins relatifs à la racine du service). */
    void writeArchive(ServiceTemplate template, TemplateContext context, OutputStream out) throws IOException;
}
