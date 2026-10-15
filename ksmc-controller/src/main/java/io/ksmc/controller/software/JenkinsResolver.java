package io.ksmc.controller.software;

import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.api.spi.SoftwareResolver;
import java.io.IOException;
import java.util.List;

/**
 * Résolveur {@code jenkins} : artefact d'un job Jenkins, version = numéro de build (BungeeCord).
 * Paramètres : {@code url} (job, défaut BungeeCord de md-5), {@code artifact} (chemin relatif).
 */
public final class JenkinsResolver implements SoftwareResolver {
    static final String DEFAULT_JOB = "https://ci.md-5.net/job/BungeeCord";
    static final String DEFAULT_ARTIFACT = "bootstrap/target/BungeeCord.jar";

    @Override
    public String type() {
        return "jenkins";
    }

    @Override
    public List<String> versions(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        return List.of(lastSuccessful(def, ctx));
    }

    private static String lastSuccessful(SoftwareDefinition def, ResolverContext ctx) throws IOException {
        return ctx.getString(Resolvers.param(def, "url", DEFAULT_JOB) + "/lastSuccessfulBuild/buildNumber").trim();
    }

    @Override
    public ResolvedSoftware resolve(SoftwareDefinition def, String requested, ResolverContext ctx) throws IOException {
        String job = Resolvers.param(def, "url", DEFAULT_JOB);
        String build = "latest".equals(requested) ? lastSuccessful(def, ctx) : requested;
        if (!build.matches("\\d+")) {
            throw new VersionNotFoundException("jenkins versions are build numbers, got: " + requested);
        }
        String url = job + "/" + build + "/artifact/" + Resolvers.param(def, "artifact", DEFAULT_ARTIFACT);
        String cacheKey = "jenkins/" + def.getMetadata().getName() + "/" + build + "/server.jar";
        return new ResolvedSoftware(build, build, new ArtifactPlan.Download(url, null, "server.jar", cacheKey), 0,
                ResolvedSoftware.jarLaunch());
    }
}
