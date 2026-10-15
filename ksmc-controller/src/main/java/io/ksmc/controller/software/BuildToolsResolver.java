package io.ksmc.controller.software;

import io.ksmc.api.model.MinecraftVersions;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résolveur {@code buildtools} : Spigot (ou CraftBukkit avec {@code params.compile=CRAFTBUKKIT}) construit par
 * BuildTools dans un Job, une seule fois par version. Spigot ne peut pas être redistribué : c'est la voie officielle.
 */
public final class BuildToolsResolver implements SoftwareResolver {
    static final String VERSIONS = "https://hub.spigotmc.org/versions/";
    static final String BUILDTOOLS =
            "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar";
    private static final Pattern VERSION_FILE = Pattern.compile("href=\"(\\d+(?:\\.\\d+)+)\\.json\"");

    @Override
    public String type() {
        return "buildtools";
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        List<String> out = new ArrayList<>();
        Matcher m = VERSION_FILE.matcher(ctx.getString(Resolvers.param(def, "versions", VERSIONS)));
        while (m.find()) {
            out.add(m.group(1));
        }
        out.sort(MinecraftVersions.COMPARATOR.reversed());
        return out.stream().distinct().toList();
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        List<String> known = versions(def, ctx);
        String version = "latest".equals(requested) ? known.getFirst() : requested;
        if (!known.contains(version)) {
            throw new VersionNotFoundException("BuildTools does not know version " + version);
        }
        String compile = Resolvers.param(def, "compile", "SPIGOT");
        String jar = (compile.equalsIgnoreCase("CRAFTBUKKIT") ? "craftbukkit-" : "spigot-") + version + ".jar";
        int java = Resolvers.javaFor(def, version);
        ArtifactPlan plan = new ArtifactPlan.BuildJob(java,
                Map.of(Resolvers.param(def, "buildtools", BUILDTOOLS), "BuildTools.jar"),
                List.of("-jar", "BuildTools.jar", "--rev", version, "--compile", compile.toUpperCase(),
                        "--output-dir", "out", "--final-name", jar),
                "out", "buildtools/" + compile.toLowerCase() + "/" + version + "/server.zip");
        return new ResolvedSoftware(version, null, plan, java, List.of("-jar", jar, "nogui"));
    }
}
