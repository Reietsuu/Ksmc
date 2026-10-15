package io.ksmc.api.spi;

import io.ksmc.api.model.SoftwareDefinition;
import java.io.IOException;
import java.util.List;

/**
 * Sait obtenir un logiciel serveur/proxy à partir d'une {@link SoftwareDefinition}
 * ({@code spec.resolver.type}). Intégrés : {@code url}, {@code papermc}, {@code jenkins}, {@code buildtools},
 * {@code fabric-meta}, {@code forge-installer}, {@code neoforge-installer}. Un module peut en ajouter.
 */
public interface SoftwareResolver {

    /** Valeur de {@code spec.resolver.type} gérée. */
    String type();

    /** Versions disponibles, les plus récentes d'abord (peut être tronqué). */
    List<String> versions(SoftwareDefinition definition, ResolverContext context) throws IOException;

    /**
     * Résout une version demandée : exacte, {@code latest} (dernière stable) ou {@code <version>@<build>}.
     *
     * @throws VersionNotFoundException si la version n'existe pas
     */
    ResolvedSoftware resolve(SoftwareDefinition definition, String requested, ResolverContext context) throws IOException;

    /** La version demandée n'existe pas pour ce logiciel. */
    class VersionNotFoundException extends IOException {
        public VersionNotFoundException(String message) {
            super(message);
        }
    }
}
