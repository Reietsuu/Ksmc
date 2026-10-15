package io.ksmc.wrapper;

import io.ksmc.wire.Wire;
import io.ksmc.wire.manifest.ServiceManifest;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Sous-commande {@code prepare} : construit le dossier du service avant le lancement. */
final class Preparer {
    /** Marqueur : templates déjà appliqués (services statiques : appliqués au premier démarrage seulement). */
    static final String PREPARED_MARKER = "prepared";
    static final File TERMINATION_LOG = new File("/dev/termination-log");
    private static final long MANIFEST_TIMEOUT_MS = 30 * 60 * 1000L;

    private final WrapperEnv env;
    private final InternalClient client;
    private final ConfigPatcher patcher = new ConfigPatcher();

    Preparer(WrapperEnv env, InternalClient client) {
        this.env = env;
        this.client = client;
    }

    int prepare() {
        try {
            ServiceManifest manifest = fetchManifest();
            apply(manifest);
            Log.info("service " + env.service + " prêt à démarrer");
            return 0;
        } catch (PrepareException e) {
            fail(e.reason, e.getMessage());
            return 1;
        } catch (Exception e) {
            fail("PrepareFailed", e.toString());
            return 1;
        }
    }

    /** Écrit la raison dans le message de terminaison Kubernetes, lu par le controller pour le statut du service. */
    private static void fail(String reason, String message) {
        Log.warn(reason + ": " + message);
        try {
            if (TERMINATION_LOG.getParentFile().isDirectory()) {
                IoUtil.writeString(TERMINATION_LOG, reason + ": " + message);
            }
        } catch (IOException ignored) {
            // pas de termination-log hors Kubernetes
        }
    }

    ServiceManifest fetchManifest() throws Exception {
        String path = "/internal/v1/services/" + env.namespace + "/" + env.service + "/manifest";
        long deadline = System.currentTimeMillis() + MANIFEST_TIMEOUT_MS;
        while (true) {
            try {
                return Wire.fromJson(client.getString(path), ServiceManifest.class);
            } catch (InternalClient.RetryLaterException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new PrepareException("ManifestTimeout", e.getMessage());
                }
                Log.info("en attente du controller : " + e.getMessage());
                Thread.sleep(e.seconds * 1000L);
            } catch (InternalClient.HttpException e) {
                // 409 : le controller indique une erreur de configuration "<Raison>: <détail>".
                int colon = e.body.indexOf(": ");
                if (e.status == 409 && colon > 0 && e.body.substring(0, colon).matches("[A-Z][A-Za-z]+")) {
                    throw new PrepareException(e.body.substring(0, colon), e.body.substring(colon + 2));
                }
                throw new PrepareException("ManifestUnavailable", e.getMessage());
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new PrepareException("ControllerUnreachable", e.getMessage());
                }
                Log.info("controller injoignable (" + e.getMessage() + "), nouvel essai");
                Thread.sleep(3000);
            }
        }
    }

    void apply(ServiceManifest manifest) throws Exception {
        File dir = env.serverDir;
        IoUtil.mkdirs(env.stateDir());
        File marker = new File(env.stateDir(), PREPARED_MARKER);
        boolean applyTemplates = !(manifest.staticService && marker.isFile());

        for (ServiceManifest.Artifact artifact : manifest.artifacts) {
            installArtifact(artifact, dir);
        }
        if (applyTemplates) {
            for (ServiceManifest.Template template : manifest.templates) {
                applyTemplate(template, dir);
            }
        } else {
            Log.info("service statique déjà initialisé : templates non réappliqués");
        }
        patcher.apply(manifest, dir);
        IoUtil.writeString(env.manifestFile(), Wire.toJson(manifest));
        IoUtil.writeString(marker, String.valueOf(System.currentTimeMillis()));
    }

    private void installArtifact(ServiceManifest.Artifact artifact, File dir) throws Exception {
        try {
            if (artifact.extract) {
                File tmp = new File(env.stateDir(), "artifact.zip");
                client.download(artifact.url, tmp, artifact.sha256);
                File target = artifact.target == null ? dir : IoUtil.safeChild(dir, artifact.target);
                InputStream in = new FileInputStream(tmp);
                try {
                    Log.info("logiciel extrait : " + IoUtil.unzip(in, target) + " fichiers");
                } finally {
                    in.close();
                    tmp.delete();
                }
            } else {
                client.download(artifact.url, IoUtil.safeChild(dir, artifact.target), artifact.sha256);
                Log.info("logiciel téléchargé : " + artifact.target);
            }
        } catch (IOException e) {
            throw new PrepareException("SoftwareDownloadFailed", e.getMessage());
        }
    }

    private void applyTemplate(ServiceManifest.Template template, File dir) throws Exception {
        try {
            int files;
            if (template.localPath != null) {
                File src = new File(template.localPath);
                if (!src.isDirectory()) {
                    throw new IOException("directory not found: " + template.localPath);
                }
                files = IoUtil.copyTree(src, dir);
            } else {
                File tmp = new File(env.stateDir(), "template.zip");
                client.download(template.url, tmp, null);
                InputStream in = new FileInputStream(tmp);
                try {
                    files = IoUtil.unzip(in, dir);
                } finally {
                    in.close();
                    tmp.delete();
                }
            }
            Log.info("template " + template.name + " appliqué (" + files + " fichiers)");
        } catch (IOException e) {
            if (template.optional) {
                Log.warn("template optionnel " + template.name + " ignoré : " + e.getMessage());
                return;
            }
            throw new PrepareException("TemplateUnavailable", template.name + ": " + e.getMessage());
        }
    }

    static final class PrepareException extends Exception {
        final String reason;

        PrepareException(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
