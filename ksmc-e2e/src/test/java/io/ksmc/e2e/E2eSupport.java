package io.ksmc.e2e;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.file.Path;

/** Accès partagé au cluster de test (kubeconfig courant). */
final class E2eSupport {
    static final String NAMESPACE = System.getProperty("ksmc.namespace", "ksmc");
    static final Path EXAMPLES = Path.of(System.getProperty("ksmc.examples", "examples"));
    static final KubernetesClient CLIENT = new KubernetesClientBuilder().build();

    /** Attend une condition (sondage toutes les 2 s) ; échoue avec la description après le délai. */
    static void await(String description, java.time.Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout: " + description);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Modifie une ServiceTask en réessayant si le controller l'a mise à jour entre-temps (conflit 409 :
     * le statut change pendant les réconciliations).
     */
    static void editTask(String name, java.util.function.Consumer<io.ksmc.api.model.ServiceTask> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                CLIENT.resources(io.ksmc.api.model.ServiceTask.class).inNamespace(NAMESPACE).withName(name).edit(t -> {
                    change.accept(t);
                    return t;
                });
                return;
            } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
                if (e.getCode() != 409 || attempt >= 10) {
                    throw e;
                }
            }
        }
    }

    /** Exécute une commande shell dans le conteneur {@code server} d'un Pod et renvoie sa sortie. */
    static String exec(String pod, String command) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (var watch = CLIENT.pods().inNamespace(NAMESPACE).withName(pod).inContainer("server")
                .writingOutput(out).exec("sh", "-c", command)) {
            watch.exitCode().get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("exec failed: " + command, e);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private E2eSupport() {
    }
}
