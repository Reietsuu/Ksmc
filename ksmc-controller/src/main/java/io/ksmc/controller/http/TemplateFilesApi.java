package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.template.StorageTemplateSource;
import io.ksmc.controller.template.ZipArchives;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fichiers des templates {@code storage} (Template Store) :
 * <ul>
 *   <li>{@code GET /api/v1/templates/<chemin>/files} : liste ;</li>
 *   <li>{@code GET|PUT|DELETE /api/v1/templates/<chemin>/files/<fichier>} : lire, écrire, supprimer ;</li>
 *   <li>{@code PATCH /api/v1/templates/<chemin>/files/<fichier>} {@code {"path": "nouveau"}} : renommer ;</li>
 *   <li>{@code GET|PUT /api/v1/templates/<chemin>/archive} : archive ZIP (le PUT accepte aussi tar.gz et remplace le contenu,
 *       {@code ?mode=merge} pour fusionner).</li>
 * </ul>
 * Le chemin du template peut contenir des {@code /} ; le premier segment {@code files} ou {@code archive} sépare le
 * chemin du fichier.
 */
public final class TemplateFilesApi implements HttpRoutes {

    private final ArtifactStorage storage;
    private final AuditLog audit;

    public TemplateFilesApi(ArtifactStorage storage, AuditLog audit) {
        this.storage = storage;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        String path = "/api/v1/templates/<rest>";
        routes.get(path, ctx -> dispatch(ctx, "GET"));
        routes.put(path, ctx -> dispatch(ctx, "PUT"));
        routes.patch(path, ctx -> dispatch(ctx, "PATCH"));
        routes.delete(path, ctx -> dispatch(ctx, "DELETE"));
    }

    /** Chemin du template et, le cas échéant, fichier visé. */
    record Target(String template, String section, String file) {
    }

    static Target parse(String rest) {
        String[] parts = rest.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("files") || parts[i].equals("archive")) {
                String template = String.join("/", java.util.Arrays.copyOfRange(parts, 0, i));
                String file = i + 1 < parts.length ? String.join("/", java.util.Arrays.copyOfRange(parts, i + 1, parts.length))
                        : null;
                if (parts[i].equals("archive") && file != null) {
                    break;
                }
                return new Target(ArtifactStorage.normalize(template), parts[i],
                        file == null ? null : ArtifactStorage.normalize(file));
            }
        }
        throw ApiException.notFound("chemin attendu : /api/v1/templates/<chemin>/files[/<fichier>] ou .../archive");
    }

    static String prefix(String template) {
        return StorageTemplateSource.PREFIX + template + "/";
    }

    private void dispatch(Context ctx, String method) throws Exception {
        Target t = parse(ctx.pathParam("rest"));
        boolean read = method.equals("GET");
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, read ? Permissions.TEMPLATE_READ : Permissions.TEMPLATE_WRITE);
        if (t.section().equals("archive")) {
            switch (method) {
                case "GET" -> downloadArchive(ctx, t);
                case "PUT" -> uploadArchive(ctx, t, p);
                default -> throw new ApiException(405, "MethodNotAllowed", "GET ou PUT sur une archive");
            }
            return;
        }
        if (t.file() == null) {
            if (!read) {
                throw new ApiException(405, "MethodNotAllowed", "nom de fichier attendu");
            }
            list(ctx, t);
            return;
        }
        String key = prefix(t.template()) + t.file();
        switch (method) {
            case "GET" -> {
                InputStream in = storage.open(key).orElseThrow(() -> ApiException.notFound("fichier introuvable : " + t.file()));
                ctx.header("Content-Length", String.valueOf(storage.size(key)));
                ctx.contentType("application/octet-stream").result(in);
            }
            case "PUT" -> {
                boolean existed = storage.exists(key);
                storage.write(key, ctx.bodyInputStream());
                audit.record(p.name(), "templates.file.write", t.template() + "/" + t.file(), "ok", null);
                ctx.status(existed ? 204 : 201);
            }
            case "DELETE" -> {
                if (!storage.exists(key) && storage.list(key + "/").isEmpty()) {
                    throw ApiException.notFound("fichier introuvable : " + t.file());
                }
                storage.delete(key);
                audit.record(p.name(), "templates.file.delete", t.template() + "/" + t.file(), "ok", null);
                ctx.status(204);
            }
            case "PATCH" -> rename(ctx, t, key, p);
            default -> throw new ApiException(405, "MethodNotAllowed", method);
        }
    }

    private void list(Context ctx, Target t) throws Exception {
        String prefix = prefix(t.template());
        List<Map<String, Object>> files = new ArrayList<>();
        for (String key : storage.list(prefix)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", key.substring(prefix.length()));
            f.put("size", storage.size(key));
            f.put("modified", java.time.Instant.ofEpochMilli(storage.lastModified(key)).toString());
            files.add(f);
        }
        ctx.json(Map.of("template", t.template(), "files", files));
    }

    private void rename(Context ctx, Target t, String key, ApiAuthenticator.Principal p) throws Exception {
        JsonNode body = ApiSupport.body(ctx);
        String to = ArtifactStorage.normalize(ApiSupport.text(body, "path"));
        String target = prefix(t.template()) + to;
        if (!storage.exists(key)) {
            throw ApiException.notFound("fichier introuvable : " + t.file());
        }
        if (storage.exists(target)) {
            throw ApiException.conflict("existe déjà : " + to);
        }
        try (InputStream in = storage.open(key).orElseThrow()) {
            storage.write(target, in);
        }
        storage.delete(key);
        audit.record(p.name(), "templates.file.rename", t.template() + "/" + t.file(), "ok", to);
        ctx.json(Map.of("path", to));
    }

    private void downloadArchive(Context ctx, Target t) throws Exception {
        String prefix = prefix(t.template());
        if (storage.list(prefix).isEmpty()) {
            throw ApiException.notFound("template vide ou inconnu : " + t.template());
        }
        ctx.contentType("application/zip").header("Content-Disposition",
                "attachment; filename=\"" + t.template().replace('/', '-') + ".zip\"");
        ZipArchives.fromStorage(storage, prefix, ctx.outputStream());
    }

    private void uploadArchive(Context ctx, Target t, ApiAuthenticator.Principal p) throws Exception {
        boolean merge = "merge".equals(ctx.queryParam("mode"));
        String prefix = prefix(t.template());
        Path tmp = Files.createTempDirectory("ksmc-template");
        int count = 0;
        try {
            try {
                ZipArchives.extract(ctx.bodyInputStream(), tmp);
            } catch (java.io.IOException e) {
                // Le contenu actuel n'est touché qu'après une extraction complète.
                throw ApiException.badRequest("archive invalide (zip ou tar.gz attendu) : " + e.getMessage());
            }
            if (!merge) {
                storage.delete(prefix);
            }
            try (var files = Files.walk(tmp)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    try (InputStream in = Files.newInputStream(f)) {
                        storage.write(prefix + tmp.relativize(f).toString().replace('\\', '/'), in);
                        count++;
                    }
                }
            }
        } finally {
            ZipArchives.deleteRecursively(tmp);
        }
        audit.record(p.name(), "templates.archive", t.template(), "ok", (merge ? "merge " : "replace ") + count);
        ctx.json(Map.of("template", t.template(), "files", count, "mode", merge ? "merge" : "replace"));
    }
}
