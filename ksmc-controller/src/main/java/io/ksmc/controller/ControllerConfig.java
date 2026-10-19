package io.ksmc.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Configuration du controller, lue depuis les variables d'environnement (posées par le chart Helm). */
public record ControllerConfig(
        String namespace,
        int httpPort,
        String natsUrl,
        String natsUser,
        String natsPassword,
        String natsServiceUser,
        String runtimeImage,
        String runtimeTag,
        String internalUrl,
        boolean acceptEula,
        String bridgesDir,
        String builtinModulesDir,
        String dataDir) {

    private static final Path SA_NAMESPACE = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");

    public static ControllerConfig fromEnv(Map<String, String> env) {
        return new ControllerConfig(
                env.getOrDefault("KSMC_NAMESPACE", currentNamespace()),
                Integer.parseInt(env.getOrDefault("KSMC_HTTP_PORT", "8080")),
                env.getOrDefault("KSMC_NATS_URL", "nats://ksmc-nats:4222"),
                env.get("KSMC_NATS_USER"),
                env.get("KSMC_NATS_PASSWORD"),
                env.get("KSMC_NATS_SERVICE_USER"),
                env.getOrDefault("KSMC_RUNTIME_IMAGE", "ghcr.io/reietsuu/ksmc-runtime"),
                env.getOrDefault("KSMC_RUNTIME_TAG", "latest"),
                env.getOrDefault("KSMC_INTERNAL_URL", "http://ksmc-controller:8080"),
                Boolean.parseBoolean(env.getOrDefault("KSMC_ACCEPT_EULA", "false")),
                env.getOrDefault("KSMC_BRIDGES_DIR", "/opt/ksmc/bridges"),
                env.getOrDefault("KSMC_BUILTIN_MODULES_DIR", "/opt/ksmc/modules"),
                env.getOrDefault("KSMC_DATA_DIR", "/data"));
    }

    /** Image runtime pour une version de Java (ex. {@code ghcr.io/reietsuu/ksmc-runtime:0.1.0-java21}). */
    public String runtimeImageFor(int javaVersion) {
        return runtimeImage + ":" + runtimeTag + "-java" + javaVersion;
    }

    private static String currentNamespace() {
        try {
            return Files.readString(SA_NAMESPACE).trim();
        } catch (Exception e) {
            return "default";
        }
    }
}
