package io.ksmc.wrapper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sous-commande {@code build} (Job Kubernetes) : télécharge les fichiers, exécute Java (BuildTools, installeur),
 * puis téléverse le dossier de sortie zippé vers le cache d'artefacts du controller.
 */
final class Builder {
    private final WrapperEnv env;
    private final InternalClient client;

    Builder(WrapperEnv env, InternalClient client) {
        this.env = env;
        this.client = client;
    }

    int build() throws Exception {
        if (env.buildSpec == null) {
            Log.warn("KSMC_BUILD manquant");
            return 2;
        }
        JsonObject spec = JsonParser.parseString(env.buildSpec).getAsJsonObject();
        File work = new File(System.getProperty("user.dir"));
        for (Map.Entry<String, JsonElement> d : spec.getAsJsonObject("downloads").entrySet()) {
            Log.info("téléchargement de " + d.getKey());
            client.download(d.getKey(), IoUtil.safeChild(work, d.getValue().getAsString()), null);
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        for (JsonElement a : spec.getAsJsonArray("javaArgs")) {
            cmd.add(a.getAsString());
        }
        Log.info("construction : " + cmd);
        Process p = new ProcessBuilder(cmd).directory(work).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) {
            Log.warn("la construction a échoué (code " + code + ")");
            return code;
        }
        File output = IoUtil.safeChild(work, spec.get("outputDir").getAsString());
        if (!output.isDirectory()) {
            Log.warn("dossier de sortie absent : " + output);
            return 1;
        }
        File zip = new File(work, "ksmc-artifact.zip");
        OutputStream out = new FileOutputStream(zip);
        try {
            IoUtil.zipTree(output, out, Collections.<String>emptyList());
        } finally {
            out.close();
        }
        String key = spec.get("cacheKey").getAsString();
        client.put("/internal/v1/artifacts/" + key, zip);
        Log.info("artefact téléversé : " + key + " (" + zip.length() + " octets)");
        return 0;
    }
}
