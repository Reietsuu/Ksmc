package io.ksmc.e2e;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.file.Path;

/** Accès partagé au cluster de test (kubeconfig courant). */
final class E2eSupport {
    static final String NAMESPACE = System.getProperty("ksmc.namespace", "ksmc");
    static final Path EXAMPLES = Path.of(System.getProperty("ksmc.examples", "examples"));
    static final KubernetesClient CLIENT = new KubernetesClientBuilder().build();

    private E2eSupport() {
    }
}
