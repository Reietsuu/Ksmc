package io.ksmc.controller.software;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.api.model.MinecraftVersions;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Résolveur {@code forge-installer} : Minecraft Forge, installé une fois par un Job ({@code --installServer}).
 * Version = version Minecraft (build « recommended », sinon « latest »), ou {@code <mc>@<forge>}.
 */
public final class ForgeInstallerResolver implements SoftwareResolver {
    static final String PROMOTIONS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    static final String MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge";

    @Override
    public String type() {
        return "forge-installer";
    }

    private static JsonNode promos(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        return Json.parse(ctx.getString(Resolvers.param(def, "promotions", PROMOTIONS))).path("promos");
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        Set<String> mc = new LinkedHashSet<>();
        for (Iterator<String> it = promos(def, ctx).fieldNames(); it.hasNext(); ) {
            String key = it.next();
            mc.add(key.substring(0, key.lastIndexOf('-')));
        }
        List<String> out = new ArrayList<>(mc);
        out.sort(MinecraftVersions.COMPARATOR.reversed());
        return out;
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        String[] parts = requested.split("@", 2);
        String mc = "latest".equals(parts[0]) ? versions(def, ctx).getFirst() : parts[0];
        String forge = parts.length == 2 ? parts[1] : null;
        if (forge == null) {
            JsonNode promos = promos(def, ctx);
            forge = promos.path(mc + "-recommended").asText(promos.path(mc + "-latest").asText(null));
            if (forge == null) {
                throw new VersionNotFoundException("no Forge build for Minecraft " + mc);
            }
        }
        String full = mc + "-" + forge;
        String maven = Resolvers.param(def, "maven", MAVEN);
        String installer = maven + "/" + full + "/forge-" + full + "-installer.jar";
        boolean modern = MinecraftVersions.compare(mc, "1.17") >= 0;
        List<String> launch = modern
                ? List.of("@libraries/net/minecraftforge/forge/" + full + "/unix_args.txt", "nogui")
                : List.of("-jar", "forge-" + full + ".jar", "nogui");
        int java = Resolvers.javaFor(def, mc);
        ArtifactPlan plan = new ArtifactPlan.BuildJob(java, Map.of(installer, "installer.jar"),
                List.of("-jar", "installer.jar", "--installServer", "server"), "server", "forge/" + full + "/server.zip");
        return new ResolvedSoftware(mc, forge, plan, java, launch);
    }
}
