package io.ksmc.controller.software;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Résolveur {@code papermc} : API Fill v3 de PaperMC (projets paper, folia, velocity, waterfall).
 * Paramètres : {@code project} (obligatoire), {@code api} (URL de base, défaut {@value #DEFAULT_API}).
 */
public final class PaperMcResolver implements SoftwareResolver {
    static final String DEFAULT_API = "https://fill.papermc.io/v3";
    private static final Pattern UNSTABLE = Pattern.compile("(?i).*-(snapshot|pre|rc|beta|alpha).*");
    private static final int LATEST_ATTEMPTS = 6;

    @Override
    public String type() {
        return "papermc";
    }

    private static String base(SoftwareDefinition def) throws IOException {
        var params = def.getSpec().resolver.params;
        String project = params.get("project");
        if (project == null) {
            throw new IOException("resolver 'papermc' requires params.project");
        }
        return params.getOrDefault("api", DEFAULT_API) + "/projects/" + project;
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        JsonNode groups = Json.parse(ctx.getString(base(def))).path("versions");
        List<String> out = new ArrayList<>();
        groups.forEach(group -> group.forEach(v -> out.add(v.asText())));
        return out;
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        String base = base(def);
        if ("latest".equals(requested)) {
            return latest(def, base, ctx);
        }
        String[] parts = requested.split("@", 2);
        String version = parts[0];
        String buildPath = parts.length == 2 ? parts[1] : "latest";
        String body = ctx.getStringOrNull(base + "/versions/" + version + "/builds/" + buildPath);
        if (body == null) {
            throw new VersionNotFoundException(def.getMetadata().getName() + " version not found: " + requested);
        }
        return fromBuild(def, base, version, Json.parse(body), ctx);
    }

    /** Dernière version sans suffixe instable dont le dernier build est STABLE ; sinon la première trouvée. */
    private ResolvedSoftware latest(SoftwareDefinition def, String base, ResolverContext ctx) throws IOException {
        List<String> all = versions(def, ctx);
        List<String> candidates = all.stream().filter(v -> !UNSTABLE.matcher(v).matches()).toList();
        if (candidates.isEmpty()) {
            candidates = all;
        }
        ResolvedSoftware fallback = null;
        for (String version : candidates.subList(0, Math.min(LATEST_ATTEMPTS, candidates.size()))) {
            String body = ctx.getStringOrNull(base + "/versions/" + version + "/builds/latest");
            if (body == null) {
                continue;
            }
            JsonNode build = Json.parse(body);
            ResolvedSoftware resolved = fromBuild(def, base, version, build, ctx);
            if ("STABLE".equals(build.path("channel").asText())) {
                return resolved;
            }
            if (fallback == null) {
                fallback = resolved;
            }
        }
        if (fallback == null) {
            throw new VersionNotFoundException("no build available for " + def.getMetadata().getName());
        }
        return fallback;
    }

    private ResolvedSoftware fromBuild(SoftwareDefinition def, String base, String version, JsonNode build,
            ResolverContext ctx) throws IOException {
        JsonNode download = build.path("downloads").path("server:default");
        if (download.isMissingNode()) {
            throw new IOException("no server download for " + version);
        }
        String buildId = build.path("id").asText();
        int java = 0;
        String meta = ctx.getStringOrNull(base + "/versions/" + version);
        if (meta != null) {
            java = Json.parse(meta).path("version").path("java").path("version").path("minimum").asInt(0);
        }
        String sha = download.path("checksums").path("sha256").asText(null);
        String cacheKey = "papermc/" + def.getSpec().resolver.params.get("project") + "/" + version + "/" + buildId + "/server.jar";
        return new ResolvedSoftware(version, buildId,
                new ArtifactPlan.Download(download.path("url").asText(), sha, "server.jar", cacheKey), java,
                ResolvedSoftware.jarLaunch());
    }
}
