package io.ksmc.controller.internal;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.controller.http.HttpRoutes;
import io.ksmc.controller.service.RetryLaterException;
import io.ksmc.controller.service.ServiceFailureException;
import io.ksmc.controller.template.StorageTemplateSource;
import io.ksmc.controller.template.ZipArchives;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * API interne ({@code /internal/v1}) utilisée par les Pods gérés par Ksmc : manifest de préparation,
 * téléchargement des fichiers, dépôt d'artefacts construits et sauvegarde vers un template.
 */
public final class InternalApi implements HttpRoutes {
    /** ServiceAccount des Pods de services. */
    public static final String SERVICE_SA = "ksmc-service";
    /** ServiceAccount des Jobs de construction (BuildTools, installeurs). */
    public static final String BUILDER_SA = "ksmc-builder";
    /** Préfixes du stockage téléchargeables par les Pods. */
    private static final List<String> READABLE = List.of("cache/", "artifacts/");

    private final PodAuthenticator auth;
    private final KubernetesClient client;
    private final ArtifactStorage storage;
    private final ManifestProvider manifests;

    public InternalApi(PodAuthenticator auth, KubernetesClient client, ArtifactStorage storage, ManifestProvider manifests) {
        this.auth = auth;
        this.client = client;
        this.storage = storage;
        this.manifests = manifests;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.get("/internal/v1/services/{ns}/{name}/manifest", this::manifest);
        routes.get("/internal/v1/files/<key>", this::download);
        routes.put("/internal/v1/artifacts/<key>", this::uploadArtifact);
        routes.put("/internal/v1/services/{ns}/{name}/deploy", this::deploy);
    }

    private PodIdentity identity(Context ctx) {
        String header = ctx.header("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        return auth.authenticate(token).orElseThrow(UnauthorizedResponse::new);
    }

    /** Vérifie que l'appelant est le Pod du service {@code ns/name}. */
    private void requireServicePod(Context ctx, String ns, String name) {
        PodIdentity id = identity(ctx);
        if (!id.namespace().equals(ns) || !id.serviceAccount().equals(SERVICE_SA)) {
            throw new ForbiddenResponse();
        }
        Pod pod = client.pods().inNamespace(ns).withName(id.podName()).get();
        if (pod == null || !name.equals(pod.getMetadata().getLabels().get(Ksmc.LABEL_SERVICE))) {
            throw new ForbiddenResponse();
        }
    }

    private void manifest(Context ctx) throws Exception {
        String ns = ctx.pathParam("ns");
        String name = ctx.pathParam("name");
        requireServicePod(ctx, ns, name);
        try {
            ctx.json(manifests.manifest(ns, name).orElseThrow(NotFoundResponse::new));
        } catch (RetryLaterException e) {
            // Logiciel en cours de téléchargement/construction : le wrapper réessaie.
            ctx.status(503).header("Retry-After", String.valueOf(e.seconds())).result(e.getMessage());
        } catch (ServiceFailureException e) {
            ctx.status(409).result(e.reason() + ": " + e.getMessage());
        }
    }

    private void download(Context ctx) throws Exception {
        PodIdentity id = identity(ctx);
        String key = ArtifactStorage.normalize(ctx.pathParam("key"));
        boolean readable = READABLE.stream().anyMatch(key::startsWith);
        // Les archives de templates sont rangées par namespace : un Pod ne lit que celles de son namespace.
        boolean otherNamespace = key.startsWith("cache/templates/") && !key.startsWith("cache/templates/" + id.namespace() + "/");
        if (!readable || otherNamespace) {
            throw new ForbiddenResponse();
        }
        Optional<InputStream> in = storage.open(key);
        if (in.isEmpty()) {
            throw new NotFoundResponse();
        }
        ctx.header("Content-Length", String.valueOf(storage.size(key)));
        ctx.contentType("application/octet-stream").result(in.get());
    }

    private void uploadArtifact(Context ctx) throws Exception {
        PodIdentity id = identity(ctx);
        if (!id.serviceAccount().equals(BUILDER_SA)) {
            throw new ForbiddenResponse();
        }
        String key = ArtifactStorage.normalize(ctx.pathParam("key"));
        storage.write("artifacts/" + key, ctx.bodyInputStream());
        ctx.status(204);
    }

    /** Remplace le contenu du template {@code storage} indiqué par l'archive ZIP envoyée par le service. */
    private void deploy(Context ctx) throws Exception {
        String ns = ctx.pathParam("ns");
        String name = ctx.pathParam("name");
        requireServicePod(ctx, ns, name);
        String template = ArtifactStorage.normalize(ctx.queryParam("template"));
        String prefix = StorageTemplateSource.PREFIX + template + "/";
        Path tmp = Files.createTempDirectory("ksmc-deploy");
        try {
            ZipArchives.extract(ctx.bodyInputStream(), tmp);
            storage.delete(prefix);
            try (var files = Files.walk(tmp)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    try (InputStream in = Files.newInputStream(f)) {
                        storage.write(prefix + tmp.relativize(f).toString().replace('\\', '/'), in);
                    }
                }
            }
        } finally {
            ZipArchives.deleteRecursively(tmp);
        }
        ctx.status(204);
    }
}
