package io.ksmc.bridge;

import io.ksmc.wire.Wire;
import io.ksmc.wire.manifest.ServiceManifest;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collections;
import java.util.Map;

/**
 * Identité et configuration du service courant, lues dans l'environnement du process (hérité du wrapper)
 * et dans le manifest écrit par le wrapper ({@code .ksmc/manifest.json}).
 */
public final class BridgeEnv {
    public final String namespace;
    public final String service;
    public final String task;
    public final String environment;
    public final String natsUrl;
    public final String natsUser;
    public final String natsPassword;
    public final ServiceManifest manifest;

    public BridgeEnv(String namespace, String service, String task, String environment, String natsUrl, String natsUser,
            String natsPassword, ServiceManifest manifest) {
        this.namespace = namespace;
        this.service = service;
        this.task = task;
        this.environment = environment;
        this.natsUrl = natsUrl;
        this.natsUser = natsUser;
        this.natsPassword = natsPassword;
        this.manifest = manifest == null ? new ServiceManifest() : manifest;
    }

    public static BridgeEnv fromSystem() {
        Map<String, String> env = System.getenv();
        String dir = env.containsKey("KSMC_SERVER_DIR") ? env.get("KSMC_SERVER_DIR") : ".";
        return new BridgeEnv(env.get("KSMC_NAMESPACE"), env.get("KSMC_SERVICE"), env.get("KSMC_TASK"),
                env.get("KSMC_ENVIRONMENT"), env.get("KSMC_NATS_URL"), env.get("KSMC_NATS_USER"),
                env.get("KSMC_NATS_PASSWORD"), readManifest(new File(dir, ".ksmc/manifest.json")));
    }

    static ServiceManifest readManifest(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            Reader r = new InputStreamReader(new FileInputStream(file), "UTF-8");
            try {
                return Wire.GSON.fromJson(r, ServiceManifest.class);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Vrai si le process tourne dans un service Ksmc (sinon le bridge reste inactif). */
    public boolean isManaged() {
        return namespace != null && service != null && natsUrl != null;
    }

    public boolean isProxy() {
        return "proxy".equals(environment);
    }

    public Map<String, String> properties() {
        return manifest.properties == null ? Collections.<String, String>emptyMap() : manifest.properties;
    }

    public String property(String key, String defaultValue) {
        String v = properties().get(key);
        return v == null ? defaultValue : v;
    }
}
