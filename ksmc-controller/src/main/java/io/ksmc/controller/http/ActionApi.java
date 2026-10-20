package io.ksmc.controller.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Ksmc;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.bridge.PlayerService;
import io.ksmc.controller.task.ServiceSelection;
import io.ksmc.wire.bridge.PlayerAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actions métier : démarrer des services d'une task, maintenance, arrêter / démarrer / redémarrer un service, envoyer
 * une commande, sauvegarder un service vers un template, actions sur les joueurs.
 */
public final class ActionApi implements HttpRoutes {

    /** Envoi d'une commande console à un service (accusé du wrapper : {@code ok} ou {@code error: ...}). */
    @FunctionalInterface
    public interface Commands {
        String send(String service, String command);
    }

    /** Préfixe des commandes de contrôle du wrapper : réservé au controller. */
    static final String CONTROL_PREFIX = "ksmc:";

    private final KubernetesClient client;
    private final String namespace;
    private final Commands commands;
    private final PlayerService players;
    private final AuditLog audit;

    public ActionApi(KubernetesClient client, String namespace, Commands commands, PlayerService players, AuditLog audit) {
        this.client = client;
        this.namespace = namespace;
        this.commands = commands;
        this.players = players;
        this.audit = audit;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.post("/api/v1/tasks/{name}/start", this::startServices);
        routes.post("/api/v1/tasks/{name}/maintenance", this::maintenance);
        routes.post("/api/v1/services/{name}/stop", ctx -> desiredState(ctx, CloudServiceSpec.DesiredState.Stopped));
        routes.post("/api/v1/services/{name}/start", ctx -> desiredState(ctx, CloudServiceSpec.DesiredState.Running));
        routes.post("/api/v1/services/{name}/restart", this::restart);
        routes.post("/api/v1/services/{name}/command", this::command);
        routes.post("/api/v1/services/{name}/deploy", this::deploy);
        routes.post("/api/v1/players/{player}/send", ctx -> player(ctx, PlayerAction.Type.SEND));
        routes.post("/api/v1/players/{player}/kick", ctx -> player(ctx, PlayerAction.Type.KICK));
        routes.post("/api/v1/players/{player}/message", ctx -> player(ctx, PlayerAction.Type.MESSAGE));
        routes.post("/api/v1/players/{player}/title", ctx -> player(ctx, PlayerAction.Type.TITLE));
    }

    private ServiceTask task(String name) {
        ServiceTask t = client.resources(ServiceTask.class).inNamespace(namespace).withName(name).get();
        if (t == null) {
            throw ApiException.notFound("tasks/" + name + " introuvable");
        }
        return t;
    }

    private CloudService service(String name) {
        CloudService s = client.resources(CloudService.class).inNamespace(namespace).withName(name).get();
        if (s == null) {
            throw ApiException.notFound("services/" + name + " introuvable");
        }
        return s;
    }

    // ---- Tasks ---------------------------------------------------------------------------------

    /** Ajoute {@code count} services au-delà du minimum ; renvoie leurs noms (plafonné par {@code maxServiceCount}). */
    private void startServices(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.TASK_START);
        String name = ctx.pathParam("name");
        JsonNode body = ApiSupport.body(ctx);
        int count = body.path("count").asInt(1);
        if (count < 1 || count > 100) {
            throw ApiException.badRequest("count entre 1 et 100");
        }
        ServiceTask task = task(name);
        ServiceTaskSpec spec = task.getSpec();
        int allowed = spec.maxServiceCount < 0 ? count
                : Math.max(0, Math.min(count, spec.maxServiceCount - spec.minServiceCount - spec.extraServices));
        if (allowed == 0) {
            audit.record(p.name(), "tasks.start", name, "error", "MaxServiceCountReached");
            throw new ApiException(409, "MaxServiceCountReached", "maxServiceCount atteint (" + spec.maxServiceCount + ")",
                    Map.of("maxServiceCount", spec.maxServiceCount));
        }
        List<String> existing = client.resources(CloudService.class).inNamespace(namespace)
                .withLabel(io.ksmc.api.model.Ksmc.LABEL_TASK, name).list().getItems().stream()
                .map(s -> s.getMetadata().getName()).toList();
        // Même règle de nommage que le reconciler : les plus petits numéros libres.
        List<String> names = ServiceSelection.nextNames(name, existing, allowed);
        client.resources(ServiceTask.class).inNamespace(namespace).withName(name).edit(t -> {
            t.getSpec().extraServices += allowed;
            return t;
        });
        audit.record(p.name(), "tasks.start", name, "ok", String.valueOf(allowed));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("services", names);
        out.put("requested", count);
        out.put("started", allowed);
        ctx.status(202).json(out);
    }

    private void maintenance(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.TASK_WRITE);
        String name = ctx.pathParam("name");
        JsonNode body = ApiSupport.body(ctx);
        if (!body.has("enabled") || !body.get("enabled").isBoolean()) {
            throw ApiException.badRequest("champ booléen obligatoire : enabled");
        }
        boolean enabled = body.get("enabled").asBoolean();
        task(name);
        client.resources(ServiceTask.class).inNamespace(namespace).withName(name).edit(t -> {
            t.getSpec().maintenance = enabled;
            return t;
        });
        audit.record(p.name(), "tasks.maintenance", name, "ok", String.valueOf(enabled));
        ctx.json(Map.of("maintenance", enabled));
    }

    // ---- Services ------------------------------------------------------------------------------

    private void desiredState(Context ctx, CloudServiceSpec.DesiredState state) {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.SERVICE_WRITE);
        String name = ctx.pathParam("name");
        CloudService svc = service(name);
        client.resources(CloudService.class).inNamespace(namespace).withName(name).edit(s -> {
            s.getSpec().desiredState = state;
            return s;
        });
        String task = svc.getSpec().task != null ? svc.getSpec().task
                : svc.getMetadata().getLabels() == null ? null : svc.getMetadata().getLabels().get(Ksmc.LABEL_TASK);
        if (state == CloudServiceSpec.DesiredState.Stopped && !svc.getSpec().staticService && task != null) {
            releaseExtraService(task);
        }
        String verb = state == CloudServiceSpec.DesiredState.Stopped ? "stop" : "start";
        audit.record(p.name(), "services." + verb, name, "ok", null);
        ctx.status(202).json(Map.of("service", name, "desiredState", state.name()));
    }

    /**
     * Arrêt manuel d'un service dynamique : il consomme d'abord un service supplémentaire demandé par « start »
     * ({@code spec.extraServices}), qui n'est donc pas recréé ; sous le minimum, le service est remplacé.
     */
    private void releaseExtraService(String taskName) {
        ServiceTask task = client.resources(ServiceTask.class).inNamespace(namespace).withName(taskName).get();
        if (task == null || task.getSpec().extraServices <= 0) {
            return;
        }
        client.resources(ServiceTask.class).inNamespace(namespace).withName(taskName).edit(t -> {
            t.getSpec().extraServices = Math.max(0, t.getSpec().extraServices - 1);
            return t;
        });
    }

    /** Redémarrage : le Pod est arrêté proprement et recréé par le controller. */
    private void restart(Context ctx) {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.SERVICE_WRITE);
        String name = ctx.pathParam("name");
        CloudService svc = service(name);
        String pod = svc.getStatus() == null || svc.getStatus().podName == null ? name : svc.getStatus().podName;
        client.pods().inNamespace(namespace).withName(pod).delete();
        audit.record(p.name(), "services.restart", name, "ok", null);
        ctx.status(202).json(Map.of("service", name));
    }

    private String send(String service, String command) {
        try {
            String reply = commands.send(service, command);
            if (reply != null && reply.startsWith("error")) {
                throw new ApiException(409, "CommandRejected", reply);
            }
            return reply;
        } catch (IllegalStateException e) {
            throw new ApiException(503, "Unavailable", e.getMessage());
        }
    }

    private void command(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.SERVICE_COMMAND);
        String name = ctx.pathParam("name");
        String command = ApiSupport.text(ApiSupport.body(ctx), "command").trim();
        if (command.startsWith(CONTROL_PREFIX)) {
            throw ApiException.badRequest("préfixe réservé : " + CONTROL_PREFIX);
        }
        service(name);
        send(name, command);
        audit.record(p.name(), "services.command", name, "ok", command);
        ctx.status(202).json(Map.of("service", name, "command", command));
    }

    /** Sauvegarde immédiate du dossier du service dans un template {@code storage}. */
    private void deploy(Context ctx) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.SERVICE_COMMAND);
        ApiSupport.require(ctx, Permissions.TEMPLATE_WRITE);
        String name = ctx.pathParam("name");
        String template = ApiSupport.text(ApiSupport.body(ctx), "template");
        if (!template.matches("[A-Za-z0-9._/-]+") || template.contains("..") || template.startsWith("/")) {
            throw ApiException.badRequest("chemin de template invalide : " + template);
        }
        service(name);
        send(name, "ksmc:deploy " + template);
        audit.record(p.name(), "services.deploy", name, "ok", template);
        ctx.status(202).json(Map.of("service", name, "template", template));
    }

    // ---- Joueurs -------------------------------------------------------------------------------

    private void player(Context ctx, PlayerAction.Type type) throws Exception {
        ApiAuthenticator.Principal p = ApiSupport.require(ctx, Permissions.PLAYER_ACTION);
        String player = ctx.pathParam("player");
        JsonNode body = ApiSupport.body(ctx);
        PlayerAction action = PlayerAction.of(type, player);
        switch (type) {
            case SEND -> {
                action.targetService = body.path("service").asText(null);
                action.targetTask = body.path("task").asText(null);
                if (action.targetService == null && action.targetTask == null) {
                    throw ApiException.badRequest("service ou task obligatoire");
                }
            }
            case KICK -> action.text = body.path("reason").asText(null);
            case MESSAGE -> action.text = ApiSupport.text(body, "text");
            case TITLE -> {
                action.text = ApiSupport.text(body, "title");
                action.subtitle = body.path("subtitle").asText(null);
            }
        }
        String reply;
        try {
            reply = players.act(action);
        } catch (IllegalStateException e) {
            throw new ApiException(503, "Unavailable", e.getMessage());
        }
        String verb = type.name().toLowerCase();
        if (reply.startsWith("error: player not online")) {
            audit.record(p.name(), "players." + verb, player, "error", reply);
            throw ApiException.notFound("joueur non connecté : " + player);
        }
        if (!"ok".equals(reply)) {
            audit.record(p.name(), "players." + verb, player, "error", reply);
            throw new ApiException(409, "ActionFailed", reply);
        }
        audit.record(p.name(), "players." + verb, player, "ok", null);
        ctx.json(Map.of("player", player, "action", verb));
    }
}
