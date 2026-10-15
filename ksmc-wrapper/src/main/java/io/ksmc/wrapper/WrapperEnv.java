package io.ksmc.wrapper;

import java.io.File;
import java.util.Map;

/** Configuration du wrapper, fournie par le Pod (variables d'environnement). */
public final class WrapperEnv {
    public final String controllerUrl;
    public final File tokenFile;
    public final String namespace;
    public final String service;
    public final File serverDir;
    public final int healthPort;
    public final String buildSpec;
    public final String task;
    public final String environment;
    public final String natsUrl;
    public final String natsUser;
    public final String natsPassword;

    public WrapperEnv(String controllerUrl, File tokenFile, String namespace, String service, File serverDir,
            int healthPort, String buildSpec) {
        this(controllerUrl, tokenFile, namespace, service, serverDir, healthPort, buildSpec, null, null, null, null, null);
    }

    public WrapperEnv(String controllerUrl, File tokenFile, String namespace, String service, File serverDir,
            int healthPort, String buildSpec, String task, String environment, String natsUrl, String natsUser,
            String natsPassword) {
        this.controllerUrl = controllerUrl;
        this.tokenFile = tokenFile;
        this.namespace = namespace;
        this.service = service;
        this.serverDir = serverDir;
        this.healthPort = healthPort;
        this.buildSpec = buildSpec;
        this.task = task;
        this.environment = environment;
        this.natsUrl = natsUrl;
        this.natsUser = natsUser;
        this.natsPassword = natsPassword;
    }

    public static WrapperEnv fromSystem() {
        return from(System.getenv());
    }

    public static WrapperEnv from(Map<String, String> env) {
        return new WrapperEnv(
                get(env, "KSMC_CONTROLLER_URL", "http://ksmc-controller:8080"),
                new File(get(env, "KSMC_TOKEN_FILE", "/var/run/ksmc/token")),
                get(env, "KSMC_NAMESPACE", "default"),
                get(env, "KSMC_SERVICE", "unknown"),
                new File(get(env, "KSMC_SERVER_DIR", "/server")),
                Integer.parseInt(get(env, "KSMC_HEALTH_PORT", "8090")),
                env.get("KSMC_BUILD"),
                env.get("KSMC_TASK"),
                env.get("KSMC_ENVIRONMENT"),
                env.get("KSMC_NATS_URL"),
                env.get("KSMC_NATS_USER"),
                env.get("KSMC_NATS_PASSWORD"));
    }

    private static String get(Map<String, String> env, String key, String def) {
        String v = env.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /** Dossier interne de Ksmc dans le dossier du service. */
    public File stateDir() {
        return new File(serverDir, ".ksmc");
    }

    public File manifestFile() {
        return new File(stateDir(), "manifest.json");
    }
}
