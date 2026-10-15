package io.ksmc.controller.software;

import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Résolveur {@code url} : téléchargement direct. Paramètres :
 * {@code url} (modèle avec {@code {version}}), {@code versions} (liste séparée par des virgules, la première = latest),
 * {@code sha256} (optionnel, seulement si une seule version).
 */
public final class UrlResolver implements SoftwareResolver {

    @Override
    public String type() {
        return "url";
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) {
        String list = def.getSpec().resolver.params.get("versions");
        return list == null ? List.of() : Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        Map<String, String> params = def.getSpec().resolver.params;
        String template = params.get("url");
        if (template == null) {
            throw new IOException("resolver 'url' requires params.url");
        }
        String version = requested;
        if ("latest".equals(requested)) {
            List<String> known = versions(def, ctx);
            if (known.isEmpty()) {
                throw new VersionNotFoundException("resolver 'url' needs params.versions to resolve 'latest'");
            }
            version = known.getFirst();
        }
        String url = template.replace("{version}", version);
        String cacheKey = "url/" + def.getMetadata().getName() + "/" + version + "/server.jar";
        return new ResolvedSoftware(version, null,
                new ArtifactPlan.Download(url, params.get("sha256"), "server.jar", cacheKey), 0, ResolvedSoftware.jarLaunch());
    }
}
