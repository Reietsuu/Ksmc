package io.ksmc.controller.internal;

import java.util.Optional;

/** Construit le manifest de préparation d'un service (implémenté avec le cycle de vie des services). */
@FunctionalInterface
public interface ManifestProvider {
    /** Manifest du service, vide s'il n'existe pas. Peut lever une exception si la préparation est impossible. */
    Optional<Object> manifest(String namespace, String serviceName) throws Exception;
}
