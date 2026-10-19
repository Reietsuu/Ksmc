package io.ksmc.controller.http;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.module.RestRoutes;
import io.ksmc.controller.module.ModuleExtensions;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extensions d'interface des modules : routes REST {@code /api/v1/modules/<id>/...}, descripteurs CLI
 * ({@code GET /api/v1/modules/<id>/cli}), pages du dashboard ({@code GET /api/v1/ui/modules}) et leurs bundles
 * ({@code GET /modules/<id>/ui.js}). Une route disparaît avec son module (404).
 */
public final class ModuleApi implements HttpRoutes {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleApi.class);
    static final String PREFIX = "/api/v1/modules/";

    private final ExtensionRegistry extensions;
    private final ApiAuthenticator auth;

    public ModuleApi(ExtensionRegistry extensions, ApiAuthenticator auth) {
        this.extensions = extensions;
        this.auth = auth;
    }

    @Override
    public void register(RoutesConfig routes) {
        routes.get("/api/v1/ui/modules", this::pages);
        routes.get("/modules/{id}/ui.js", this::bundle);
        routes.get(PREFIX + "{id}/cli", this::cli);
        routes.get("/api/v1/cli/commands", this::allCli);
        for (RestRoutes.Method m : RestRoutes.Method.values()) {
            switch (m) {
                case GET -> routes.get(PREFIX + "{id}/<path>", ctx -> dispatch(ctx, m));
                case POST -> routes.post(PREFIX + "{id}/<path>", ctx -> dispatch(ctx, m));
                case PUT -> routes.put(PREFIX + "{id}/<path>", ctx -> dispatch(ctx, m));
                case PATCH -> routes.patch(PREFIX + "{id}/<path>", ctx -> dispatch(ctx, m));
                case DELETE -> routes.delete(PREFIX + "{id}/<path>", ctx -> dispatch(ctx, m));
            }
        }
    }

    private ApiAuthenticator.Principal principal(Context ctx) {
        return auth.authenticate(ctx).orElse(null);
    }

    private void pages(Context ctx) {
        if (principal(ctx) == null) {
            ctx.status(401).json(Map.of("error", "authentification requise"));
            return;
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (ModuleExtensions.Page p : extensions.all(ModuleExtensions.Page.class)) {
            Map<String, String> page = new LinkedHashMap<>();
            page.put("module", p.moduleId());
            page.put("title", p.page().title());
            page.put("icon", p.page().icon());
            page.put("bundle", "/modules/" + p.moduleId() + "/ui.js");
            out.add(page);
        }
        ctx.json(out);
    }

    /** Bundle JavaScript du module (code statique, servi sans authentification comme le reste du dashboard). */
    private void bundle(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        for (ModuleExtensions.Page p : extensions.all(ModuleExtensions.Page.class)) {
            if (p.moduleId().equals(id)) {
                String resource = p.page().bundleResource().replaceFirst("^/", "");
                InputStream in = p.classLoader().getResourceAsStream(resource);
                if (in != null) {
                    ctx.contentType("text/javascript; charset=utf-8").result(in);
                    return;
                }
            }
        }
        ctx.status(404).result("module sans page : " + id);
    }

    private void cli(Context ctx) {
        if (principal(ctx) == null) {
            ctx.status(401).json(Map.of("error", "authentification requise"));
            return;
        }
        String id = ctx.pathParam("id");
        ctx.json(extensions.all(ModuleExtensions.Cli.class).stream().filter(c -> c.moduleId().equals(id))
                .map(ModuleExtensions.Cli::command).toList());
    }

    /** Commandes CLI de tous les modules actifs : {@code {"<module>": [commande...]}} (découverte par la CLI). */
    private void allCli(Context ctx) {
        if (principal(ctx) == null) {
            ctx.status(401).json(Map.of("error", "authentification requise"));
            return;
        }
        Map<String, List<io.ksmc.api.module.CliCommand>> out = new java.util.TreeMap<>();
        for (ModuleExtensions.Cli c : extensions.all(ModuleExtensions.Cli.class)) {
            out.computeIfAbsent(c.moduleId(), k -> new ArrayList<>()).add(c.command());
        }
        ctx.json(out);
    }

    private void dispatch(Context ctx, RestRoutes.Method method) {
        ApiAuthenticator.Principal user = principal(ctx);
        if (user == null) {
            ctx.status(401).json(Map.of("error", "authentification requise"));
            return;
        }
        String id = ctx.pathParam("id");
        String path = ctx.pathParam("path");
        ModuleExtensions.Route route = null;
        Map<String, String> params = null;
        for (ModuleExtensions.Route r : extensions.all(ModuleExtensions.Route.class)) {
            if (r.moduleId().equals(id) && r.method() == method) {
                params = match(r.path(), path);
                if (params != null) {
                    route = r;
                    break;
                }
            }
        }
        if (route == null) {
            ctx.status(404).json(Map.of("error", "route inconnue : " + method + " " + PREFIX + id + "/" + path));
            return;
        }
        if (!user.has(route.permission())) {
            ctx.status(403).json(Map.of("error", "permission requise : " + route.permission()));
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        ctx.headerMap().forEach((k, v) -> {
            if (!k.equalsIgnoreCase("Authorization") && !k.equalsIgnoreCase("Cookie")) {
                headers.put(k, v);
            }
        });
        RestRoutes.Request request = new RestRoutes.Request(method, path, Map.copyOf(params),
                Map.copyOf(ctx.queryParamMap()), headers, ctx.body(), user.name());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(route.classLoader());
        try {
            RestRoutes.Response response = route.handler().handle(request);
            if (response == null) {
                ctx.status(204);
            } else if (response.body() == null) {
                ctx.status(response.status());
            } else if (response.body() instanceof String text) {
                ctx.status(response.status()).contentType("text/plain; charset=utf-8").result(text);
            } else {
                ctx.status(response.status()).json(response.body());
            }
        } catch (Exception | LinkageError e) {
            LOG.warn("Module {} : erreur dans {} {} : {}", id, method, path, e.toString());
            ctx.status(500).json(Map.of("error", "erreur du module " + id + " : " + e.getClass().getSimpleName()));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Paramètres si {@code actual} correspond au motif ({@code a/{x}/b}), sinon {@code null}. */
    static Map<String, String> match(String pattern, String actual) {
        String[] p = pattern.replaceAll("^/|/$", "").split("/", -1);
        String[] a = actual.replaceAll("^/|/$", "").split("/", -1);
        if (p.length != a.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < p.length; i++) {
            if (p[i].startsWith("{") && p[i].endsWith("}") && !a[i].isEmpty()) {
                params.put(p[i].substring(1, p[i].length() - 1), a[i]);
            } else if (!p[i].equals(a[i])) {
                return null;
            }
        }
        return params;
    }
}
