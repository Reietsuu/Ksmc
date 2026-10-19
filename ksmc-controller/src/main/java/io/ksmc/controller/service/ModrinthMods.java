package io.ksmc.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolverContext;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Résolution de mods sur Modrinth (ex. FabricProxy-Lite et Fabric API pour le forwarding Velocity sur Fabric). */
public final class ModrinthMods {
    static final String API = "https://api.modrinth.com/v2";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ResolverContext http;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<ArtifactPlan.Download> plan, Instant expires) {
    }

    public ModrinthMods(ResolverContext http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /** Dernière version du projet compatible avec ce loader et cette version de Minecraft, déposée dans {@code mods/}. */
    public Optional<ArtifactPlan.Download> latest(String project, String loader, String gameVersion) throws IOException {
        String key = project + "|" + loader + "|" + gameVersion;
        Cached c = cache.get(key);
        Instant now = clock.instant();
        if (c != null && c.expires().isAfter(now)) {
            return c.plan();
        }
        String url = API + "/project/" + project + "/version?loaders=" + enc("[\"" + loader + "\"]")
                + "&game_versions=" + enc("[\"" + gameVersion + "\"]");
        JsonNode versions = JSON.readTree(http.getString(url));
        Optional<ArtifactPlan.Download> plan = Optional.empty();
        if (versions.isArray() && !versions.isEmpty()) {
            JsonNode v = versions.get(0);
            JsonNode file = v.path("files").get(0);
            for (JsonNode f : v.path("files")) {
                if (f.path("primary").asBoolean()) {
                    file = f;
                }
            }
            String name = file.path("filename").asText();
            plan = Optional.of(new ArtifactPlan.Download(file.path("url").asText(), null, "mods/" + name,
                    "modrinth/" + project + "/" + v.path("id").asText() + "/" + name));
        }
        cache.put(key, new Cached(plan, now.plus(CACHE_TTL)));
        return plan;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
