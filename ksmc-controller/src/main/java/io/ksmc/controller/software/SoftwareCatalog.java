package io.ksmc.controller.software;

import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre des {@link SoftwareResolver} (intégrés + modules) et cache court des résolutions,
 * pour ne pas solliciter les APIs externes à chaque démarrage de service.
 */
public final class SoftwareCatalog {
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final Map<String, SoftwareResolver> resolvers = new ConcurrentHashMap<>();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final ResolverContext context;
    private final Clock clock;

    private record Cached(ResolvedSoftware value, Instant expires) {
    }

    public SoftwareCatalog(ResolverContext context, Clock clock) {
        this.context = context;
        this.clock = clock;
        for (SoftwareResolver r : List.of(new UrlResolver(), new PaperMcResolver(), new JenkinsResolver(),
                new FabricMetaResolver(), new ForgeInstallerResolver(), new NeoForgeInstallerResolver(),
                new BuildToolsResolver())) {
            register(r);
        }
    }

    public void register(SoftwareResolver resolver) {
        resolvers.put(resolver.type(), resolver);
    }

    public void unregister(SoftwareResolver resolver) {
        resolvers.remove(resolver.type(), resolver);
    }

    public SoftwareResolver resolverFor(SoftwareDefinition def) throws IOException {
        SoftwareResolver r = resolvers.get(def.getSpec().resolver.type);
        if (r == null) {
            throw new IOException("unknown resolver type: " + def.getSpec().resolver.type);
        }
        return r;
    }

    public List<String> versions(SoftwareDefinition def) throws IOException {
        return resolverFor(def).versions(def, context);
    }

    public ResolvedSoftware resolve(SoftwareDefinition def, String version) throws IOException {
        String key = def.getMetadata().getUid() + "/" + def.getMetadata().getResourceVersion() + "/" + version;
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.expires().isAfter(now)) {
            return cached.value();
        }
        ResolvedSoftware resolved = resolverFor(def).resolve(def, version, context);
        cache.put(key, new Cached(resolved, now.plus(CACHE_TTL)));
        return resolved;
    }
}
