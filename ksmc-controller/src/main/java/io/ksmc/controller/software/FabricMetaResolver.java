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

/**
 * Résolveur {@code fabric-meta} : lanceur serveur Fabric (meta.fabricmc.net). Version = version Minecraft,
 * {@code <mc>@<loader>} pour imposer un loader. Le lanceur télécharge le serveur vanilla au premier démarrage.
 */
public final class FabricMetaResolver implements SoftwareResolver {
    static final String DEFAULT_API = "https://meta.fabricmc.net/v2";

    @Override
    public String type() {
        return "fabric-meta";
    }

    private static String api(SoftwareDefinition def) {
        return Resolvers.param(def, "api", DEFAULT_API);
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode v : Json.parse(ctx.getString(api(def) + "/versions/game"))) {
            if (v.path("stable").asBoolean()) {
                out.add(v.path("version").asText());
            }
        }
        return out;
    }

    private static String firstStable(ResolverContext ctx, String url) throws IOException {
        JsonNode list = Json.parse(ctx.getString(url));
        for (JsonNode v : list) {
            if (v.path("stable").asBoolean()) {
                return v.path("version").asText();
            }
        }
        if (list.isEmpty()) {
            throw new IOException("empty list at " + url);
        }
        return list.get(0).path("version").asText();
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        String[] parts = requested.split("@", 2);
        String game = "latest".equals(parts[0]) ? versions(def, ctx).getFirst() : parts[0];
        JsonNode loaders = Json.parse(ctx.getString(api(def) + "/versions/loader/" + game));
        if (loaders.isEmpty()) {
            throw new VersionNotFoundException("fabric does not support Minecraft " + game);
        }
        String loader = parts.length == 2 ? parts[1] : null;
        if (loader == null) {
            for (JsonNode l : loaders) {
                if (l.path("loader").path("stable").asBoolean()) {
                    loader = l.path("loader").path("version").asText();
                    break;
                }
            }
            if (loader == null) {
                loader = loaders.get(0).path("loader").path("version").asText();
            }
        }
        String installer = firstStable(ctx, api(def) + "/versions/installer");
        String url = api(def) + "/versions/loader/" + game + "/" + loader + "/" + installer + "/server/jar";
        String cacheKey = "fabric/" + game + "/" + loader + "/" + installer + "/server.jar";
        return new ResolvedSoftware(game, loader, new ArtifactPlan.Download(url, null, "server.jar", cacheKey), 0,
                ResolvedSoftware.jarLaunch());
    }
}
