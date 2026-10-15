package io.ksmc.controller.software;

import io.ksmc.api.model.MinecraftVersions;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Résolveur {@code neoforge-installer} (Minecraft 1.20.2+). Version = version Minecraft (dernier build stable de la
 * ligne), ou {@code <mc>@<neoforge>}. Les versions NeoForge suivent la version Minecraft : 1.21.1 → 21.1.x,
 * 26.1.2 → 26.1.2.x.
 */
public final class NeoForgeInstallerResolver implements SoftwareResolver {
    static final String MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge";

    @Override
    public String type() {
        return "neoforge-installer";
    }

    private static List<String> neoVersions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        return Resolvers.mavenVersions(ctx.getString(Resolvers.param(def, "maven", MAVEN) + "/maven-metadata.xml"));
    }

    /** Préfixe NeoForge d'une version Minecraft. */
    static String prefix(String mc) {
        if (mc.startsWith("1.")) {
            String[] p = mc.substring(2).split("\\.");
            return p[0] + "." + (p.length > 1 ? p[1] : "0") + ".";
        }
        return mc + ".";
    }

    /** Version Minecraft d'une version NeoForge. */
    static String minecraftOf(String neo) {
        String[] p = neo.split("[.-]");
        if (Integer.parseInt(p[0]) < 26) {
            return "1." + p[0] + ("0".equals(p[1]) ? "" : "." + p[1]);
        }
        return neo.substring(0, neo.lastIndexOf('.'));
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        LinkedHashSet<String> mc = new LinkedHashSet<>();
        for (String v : neoVersions(def, ctx)) {
            mc.add(minecraftOf(v));
        }
        List<String> out = new ArrayList<>(mc);
        out.sort(MinecraftVersions.COMPARATOR.reversed());
        return out;
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        String[] parts = requested.split("@", 2);
        List<String> all = neoVersions(def, ctx);
        String mc = "latest".equals(parts[0]) ? minecraftOf(all.stream()
                .filter(v -> !Resolvers.UNSTABLE.matcher(v).matches()).findFirst()
                .orElseThrow(() -> new VersionNotFoundException("no NeoForge release"))) : parts[0];
        String neo = parts.length == 2 ? parts[1] : null;
        if (neo == null) {
            String prefix = prefix(mc);
            List<String> line = all.stream().filter(v -> v.startsWith(prefix)).toList();
            neo = line.stream().filter(v -> !Resolvers.UNSTABLE.matcher(v).matches()).findFirst()
                    .orElse(line.isEmpty() ? null : line.getFirst());
            if (neo == null) {
                throw new VersionNotFoundException("no NeoForge build for Minecraft " + mc);
            }
        }
        String installer = Resolvers.param(def, "maven", MAVEN) + "/" + neo + "/neoforge-" + neo + "-installer.jar";
        int java = Resolvers.javaFor(def, mc);
        ArtifactPlan plan = new ArtifactPlan.BuildJob(java, Map.of(installer, "installer.jar"),
                List.of("-jar", "installer.jar", "--installServer", "server"), "server", "neoforge/" + neo + "/server.zip");
        return new ResolvedSoftware(mc, neo, plan, java,
                List.of("@libraries/net/neoforged/neoforge/" + neo + "/unix_args.txt", "nogui"));
    }
}
