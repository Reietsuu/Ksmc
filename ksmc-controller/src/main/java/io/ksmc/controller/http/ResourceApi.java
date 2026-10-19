package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.KsmcModule;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.bridge.PlayerService;
import io.ksmc.wire.bridge.PlayerInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD des ressources Ksmc ({@code /api/v1/tasks|groups|templates|services|software|modules}) : forme des CRD
 * ({@code metadata}, {@code spec}, {@code status}), lecture directe dans Kubernetes (cohérence avec kubectl), ETag =
 * {@code resourceVersion} et {@code If-Match} pour la concurrence optimiste, pagination par curseur ({@code limit},
 * {@code continue}). Joueurs en lecture ({@code /api/v1/players}).
 */
public final class ResourceApi implements HttpRoutes {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    private static final KubernetesSerialization SERIALIZATION = new KubernetesSerialization();

    /** Type exposé : chemin, classe, permissions de lecture et d'écriture. */
    public record Kind<T extends HasMetadata>(String path, Class<T> type, String read, String write) {
    }

    static final List<Kind<?>> KINDS = List.of(
            new Kind<>("tasks", ServiceTask.class, Permissions.TASK_READ, Permissions.TASK_WRITE),
            new Kind<>("groups", ServiceGroup.class, Permissions.GROUP_READ, Permissions.GROUP_WRITE),
            new Kind<>("templates", ServiceTemplate.class, Permissions.TEMPLATE_READ, Permissions.TEMPLATE_WRITE),
            new Kind<>("services", CloudService.class, Permissions.SERVICE_READ, Permissions.SERVICE_WRITE),
            new Kind<>("software", SoftwareDefinition.class, Permissions.SOFTWARE_READ, Permissions.SOFTWARE_WRITE),
            new Kind<>("modules", KsmcModule.class, Permissions.MODULE_READ, Permissions.MODULE_WRITE));

    private final KubernetesClient client;
    private final String namespace;
    private final PlayerService players;
    private final AuditLog audit;

    public ResourceApi(KubernetesClient client, String namespace, PlayerService players, AuditLog audit) {
        this.client = client;
        this.namespace = namespace;
        this.players = players;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        for (Kind<?> kind : KINDS) {
            String base = "/api/v1/" + kind.path();
            routes.get(base, ctx -> list(ctx, kind));
            routes.post(base, ctx -> create(ctx, kind));
            routes.get(base + "/{name}", ctx -> get(ctx, kind));
            routes.put(base + "/{name}", ctx -> replace(ctx, kind));
            routes.patch(base + "/{name}", ctx -> patch(ctx, kind));
            routes.delete(base + "/{name}", ctx -> delete(ctx, kind));
        }
        routes.get("/api/v1/players", this::listPlayers);
        routes.get("/api/v1/players/{player}", this::getPlayer);
    }

    // ---- Conversion ----------------------------------------------------------------------------

    /** Ressource → JSON de l'API (sans {@code managedFields}). */
    static JsonNode dto(HasMetadata resource) {
        ObjectNode node = (ObjectNode) ApiSupport.JSON.valueToTree(SERIALIZATION.convertValue(resource, Map.class));
        if (node.get("metadata") instanceof ObjectNode meta) {
            meta.remove("managedFields");
        }
        return node;
    }

    static String etag(HasMetadata resource) {
        return "\"" + resource.getMetadata().getResourceVersion() + "\"";
    }

    /** Valeur de {@code If-Match} sans guillemets, ou {@code null}. */
    static String ifMatch(Context ctx) {
        String v = ctx.header("If-Match");
        if (v == null || v.isBlank() || v.equals("*")) {
            return null;
        }
        return v.trim().replaceAll("^W/", "").replaceAll("^\"|\"$", "");
    }

    /** Corps de requête → ressource typée (namespace imposé, statut ignoré). */
    private <T extends HasMetadata> T read(Context ctx, Kind<T> kind, String name) throws java.io.IOException {
        ObjectNode body = (ObjectNode) ApiSupport.body(ctx);
        body.remove("status");
        ObjectNode meta = body.has("metadata") && body.get("metadata").isObject() ? (ObjectNode) body.get("metadata")
                : body.putObject("metadata");
        if (name != null) {
            if (meta.hasNonNull("name") && !meta.get("name").asText().equals(name)) {
                throw ApiException.badRequest("metadata.name ne correspond pas au chemin : " + meta.get("name").asText());
            }
            meta.put("name", name);
        } else if (!meta.hasNonNull("name")) {
            throw ApiException.badRequest("metadata.name obligatoire");
        }
        meta.put("namespace", namespace);
        for (String readOnly : List.of("uid", "creationTimestamp", "generation", "managedFields", "ownerReferences",
                "deletionTimestamp", "resourceVersion")) {
            meta.remove(readOnly);
        }
        T resource = SERIALIZATION.convertValue(body, kind.type());
        if (resource.getMetadata() == null) {
            throw ApiException.badRequest("metadata invalide");
        }
        return resource;
    }

    // ---- CRUD ----------------------------------------------------------------------------------

    private <T extends HasMetadata> void list(Context ctx, Kind<T> kind) {
        ApiSupport.require(ctx, kind.read());
        int limit = DEFAULT_LIMIT;
        if (ctx.queryParam("limit") != null) {
            try {
                limit = Integer.parseInt(ctx.queryParam("limit"));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("limit doit être un entier");
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw ApiException.badRequest("limit entre 1 et " + MAX_LIMIT);
            }
        }
        String selector = ctx.queryParam("labelSelector");
        if (ctx.queryParam("task") != null) {
            String task = Ksmc.LABEL_TASK + "=" + ctx.queryParam("task");
            selector = selector == null ? task : selector + "," + task;
        }
        KubernetesResourceList<T> page = client.resources(kind.type()).inNamespace(namespace)
                .list(new ListOptionsBuilder().withLimit((long) limit).withContinue(ctx.queryParam("continue"))
                        .withLabelSelector(selector).build());
        List<JsonNode> items = new ArrayList<>();
        page.getItems().forEach(r -> items.add(dto(r)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        String next = page.getMetadata() == null ? null : page.getMetadata().getContinue();
        out.put("continue", next == null || next.isEmpty() ? null : next);
        ctx.json(out);
    }

    private <T extends HasMetadata> T existing(Kind<T> kind, String name) {
        T r = client.resources(kind.type()).inNamespace(namespace).withName(name).get();
        if (r == null) {
            throw ApiException.notFound(kind.path() + "/" + name + " introuvable");
        }
        return r;
    }

    private <T extends HasMetadata> void get(Context ctx, Kind<T> kind) {
        ApiSupport.require(ctx, kind.read());
        T r = existing(kind, ctx.pathParam("name"));
        String etag = etag(r);
        if (etag.equals(ctx.header("If-None-Match"))) {
            ctx.status(304);
            return;
        }
        ctx.header("ETag", etag).json(dto(r));
    }

    private <T extends HasMetadata> void create(Context ctx, Kind<T> kind) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, kind.write());
        T desired = read(ctx, kind, null);
        String name = desired.getMetadata().getName();
        T created;
        try {
            created = client.resource(desired).create();
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            audit.record(p.name(), kind.path() + ".create", name, "error", e.getMessage());
            if (e.getCode() == 409) {
                throw ApiException.conflict(kind.path() + "/" + name + " existe déjà");
            }
            throw e;
        }
        audit.record(p.name(), kind.path() + ".create", name, "ok", null);
        ctx.status(201).header("Location", "/api/v1/" + kind.path() + "/" + name).header("ETag", etag(created))
                .json(dto(created));
    }

    private <T extends HasMetadata> void replace(Context ctx, Kind<T> kind) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, kind.write());
        String name = ctx.pathParam("name");
        T current = existing(kind, name);
        T desired = read(ctx, kind, name);
        String expected = ifMatch(ctx);
        desired.getMetadata().setResourceVersion(expected != null ? expected : current.getMetadata().getResourceVersion());
        // Le statut et les références de propriétaire appartiennent au controller.
        desired.getMetadata().setOwnerReferences(current.getMetadata().getOwnerReferences());
        copyStatus(current, desired);
        T updated = write(p, kind, name, "update", () -> client.resource(desired).update());
        ctx.header("ETag", etag(updated)).json(dto(updated));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyStatus(HasMetadata from, HasMetadata to) {
        if (from instanceof io.fabric8.kubernetes.client.CustomResource f
                && to instanceof io.fabric8.kubernetes.client.CustomResource t) {
            t.setStatus(f.getStatus());
        }
    }

    private <T extends HasMetadata> void patch(Context ctx, Kind<T> kind) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, kind.write());
        String name = ctx.pathParam("name");
        existing(kind, name);
        ObjectNode body = (ObjectNode) ApiSupport.body(ctx);
        body.remove("status");
        body.remove("apiVersion");
        body.remove("kind");
        ObjectNode meta = body.has("metadata") && body.get("metadata").isObject() ? (ObjectNode) body.get("metadata")
                : null;
        if (meta != null) {
            for (String readOnly : List.of("name", "namespace", "uid", "creationTimestamp", "generation", "managedFields",
                    "ownerReferences", "resourceVersion")) {
                meta.remove(readOnly);
            }
        }
        String expected = ifMatch(ctx);
        if (expected != null) {
            (meta != null ? meta : body.putObject("metadata")).put("resourceVersion", expected);
        }
        T updated = write(p, kind, name, "patch", () -> client.resources(kind.type()).inNamespace(namespace)
                .withName(name).patch(PatchContext.of(PatchType.JSON_MERGE), body.toString()));
        ctx.header("ETag", etag(updated)).json(dto(updated));
    }

    private <T extends HasMetadata> void delete(Context ctx, Kind<T> kind) {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, kind.write());
        String name = ctx.pathParam("name");
        T current = existing(kind, name);
        String expected = ifMatch(ctx);
        if (expected != null && !expected.equals(current.getMetadata().getResourceVersion())) {
            audit.record(p.name(), kind.path() + ".delete", name, "error", "Conflict");
            throw ApiException.conflict(kind.path() + "/" + name + " a été modifié (ETag périmé)");
        }
        client.resources(kind.type()).inNamespace(namespace).withName(name).delete();
        audit.record(p.name(), kind.path() + ".delete", name, "ok", null);
        ctx.status(204);
    }

    private <T> T write(ApiAuthenticator.Principal p, Kind<?> kind, String name, String verb,
            java.util.function.Supplier<T> action) {
        try {
            T result = action.get();
            audit.record(p.name(), kind.path() + "." + verb, name, "ok", null);
            return result;
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            audit.record(p.name(), kind.path() + "." + verb, name, "error", e.getMessage());
            if (e.getCode() == 409) {
                throw ApiException.conflict(kind.path() + "/" + name + " a été modifié (ETag périmé)");
            }
            throw e;
        }
    }

    // ---- Joueurs -------------------------------------------------------------------------------

    private void listPlayers(Context ctx) {
        ApiSupport.require(ctx, Permissions.PLAYER_READ);
        try {
            ctx.json(Map.of("items", players.list()));
        } catch (IllegalStateException e) {
            throw new ApiException(503, "Unavailable", "registre des joueurs indisponible : " + e.getMessage());
        }
    }

    private void getPlayer(Context ctx) {
        ApiSupport.require(ctx, Permissions.PLAYER_READ);
        PlayerInfo p = players.find(ctx.pathParam("player"))
                .orElseThrow(() -> ApiException.notFound("joueur non connecté : " + ctx.pathParam("player")));
        ctx.json(p);
    }
}
