package io.ksmc.api.spi;

import io.fabric8.kubernetes.client.KubernetesClient;

/** Accès fournis aux {@link TemplateSourceProvider}. */
public record TemplateContext(String namespace, KubernetesClient kubernetes, ArtifactStorage storage) {
}
