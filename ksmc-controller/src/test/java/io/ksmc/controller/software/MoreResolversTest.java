package io.ksmc.controller.software;

import static io.ksmc.controller.software.ResolversTest.def;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.SoftwareResolver;
import org.junit.jupiter.api.Test;

class MoreResolversTest {

    @Test
    void jenkinsUsesBuildNumbers() throws Exception {
        FakeResolverContext ctx = new FakeResolverContext()
                .on("https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/buildNumber", "1950\n");
        JenkinsResolver r = new JenkinsResolver();
        SoftwareDefinition d = def("bungeecord", "{type: jenkins}");

        ResolvedSoftware latest = r.resolve(d, "latest", ctx);
        assertThat(latest.version()).isEqualTo("1950");
        assertThat(((ArtifactPlan.Download) latest.plan()).url())
                .isEqualTo("https://ci.md-5.net/job/BungeeCord/1950/artifact/bootstrap/target/BungeeCord.jar");
        assertThatThrownBy(() -> r.resolve(d, "1.21", ctx)).isInstanceOf(SoftwareResolver.VersionNotFoundException.class);
    }

    @Test
    void fabricPicksStableGameAndLoader() throws Exception {
        String api = "https://meta.fabricmc.net/v2";
        FakeResolverContext ctx = new FakeResolverContext()
                .on(api + "/versions/game", "[{\"version\":\"26.4-snapshot-4\",\"stable\":false},{\"version\":\"26.3\",\"stable\":true}]")
                .on(api + "/versions/loader/26.3", "[{\"loader\":{\"version\":\"0.20.0\",\"stable\":false}},"
                        + "{\"loader\":{\"version\":\"0.19.2\",\"stable\":true}}]")
                .on(api + "/versions/loader/9.9", "[]")
                .on(api + "/versions/installer", "[{\"version\":\"1.1.2\",\"stable\":true}]");
        FabricMetaResolver r = new FabricMetaResolver();
        SoftwareDefinition d = def("fabric", "{type: fabric-meta}");

        ResolvedSoftware res = r.resolve(d, "latest", ctx);
        assertThat(res.version()).isEqualTo("26.3");
        assertThat(res.build()).isEqualTo("0.19.2");
        assertThat(((ArtifactPlan.Download) res.plan()).url()).isEqualTo(api + "/versions/loader/26.3/0.19.2/1.1.2/server/jar");
        assertThatThrownBy(() -> r.resolve(d, "9.9", ctx)).isInstanceOf(SoftwareResolver.VersionNotFoundException.class);
    }

    @Test
    void forgeModernAndLegacyLaunch() throws Exception {
        FakeResolverContext ctx = new FakeResolverContext().on(ForgeInstallerResolver.PROMOTIONS, """
                {"promos":{"1.12.2-recommended":"14.23.5.2859","1.20.1-latest":"47.4.1","1.20.1-recommended":"47.3.0",
                "26.3-latest":"66.0.12"}}""");
        ForgeInstallerResolver r = new ForgeInstallerResolver();
        SoftwareDefinition d = def("forge", "{type: forge-installer}");

        assertThat(r.versions(d, ctx)).containsExactly("26.3", "1.20.1", "1.12.2");
        ResolvedSoftware modern = r.resolve(d, "1.20.1", ctx);
        assertThat(modern.build()).isEqualTo("47.3.0");
        assertThat(modern.launch()).containsExactly("@libraries/net/minecraftforge/forge/1.20.1-47.3.0/unix_args.txt", "nogui");
        ArtifactPlan.BuildJob job = (ArtifactPlan.BuildJob) modern.plan();
        assertThat(job.downloads()).containsValue("installer.jar");
        assertThat(job.cacheKey()).isEqualTo("forge/1.20.1-47.3.0/server.zip");

        ResolvedSoftware legacy = r.resolve(d, "1.12.2", ctx);
        assertThat(legacy.launch()).containsExactly("-jar", "forge-1.12.2-14.23.5.2859.jar", "nogui");
        assertThat(r.resolve(d, "latest", ctx).build()).isEqualTo("66.0.12");
    }

    @Test
    void neoForgeVersionMapping() throws Exception {
        assertThat(NeoForgeInstallerResolver.prefix("1.21.1")).isEqualTo("21.1.");
        assertThat(NeoForgeInstallerResolver.prefix("1.21")).isEqualTo("21.0.");
        assertThat(NeoForgeInstallerResolver.prefix("26.1.2")).isEqualTo("26.1.2.");
        assertThat(NeoForgeInstallerResolver.minecraftOf("21.1.262")).isEqualTo("1.21.1");
        assertThat(NeoForgeInstallerResolver.minecraftOf("21.0.167")).isEqualTo("1.21");
        assertThat(NeoForgeInstallerResolver.minecraftOf("26.1.2.121")).isEqualTo("26.1.2");

        FakeResolverContext ctx = new FakeResolverContext().on(NeoForgeInstallerResolver.MAVEN + "/maven-metadata.xml",
                "<metadata><versioning><versions><version>21.1.261</version><version>21.1.262</version>"
                        + "<version>26.1.2.121</version><version>26.1.2.122-beta</version></versions></versioning></metadata>");
        NeoForgeInstallerResolver r = new NeoForgeInstallerResolver();
        SoftwareDefinition d = def("neoforge", "{type: neoforge-installer}");

        assertThat(r.resolve(d, "1.21.1", ctx).build()).isEqualTo("21.1.262");
        ResolvedSoftware latest = r.resolve(d, "latest", ctx);
        assertThat(latest.version()).isEqualTo("26.1.2");
        assertThat(latest.build()).isEqualTo("26.1.2.121");
    }

    @Test
    void buildToolsParsesVersionListing() throws Exception {
        FakeResolverContext ctx = new FakeResolverContext().on(BuildToolsResolver.VERSIONS,
                "<a href=\"1.8.8.json\">x</a><a href=\"1.21.4.json\">x</a><a href=\"26.3.json\">x</a><a href=\"4648.json\">");
        BuildToolsResolver r = new BuildToolsResolver();
        SoftwareDefinition d = def("spigot", "{type: buildtools}");

        assertThat(r.versions(d, ctx)).containsExactly("26.3", "1.21.4", "1.8.8");
        ResolvedSoftware res = r.resolve(d, "1.8.8", ctx);
        assertThat(res.launch()).containsExactly("-jar", "spigot-1.8.8.jar", "nogui");
        assertThat(((ArtifactPlan.BuildJob) res.plan()).javaArgs()).contains("--rev", "1.8.8");
        assertThatThrownBy(() -> r.resolve(d, "1.99", ctx)).isInstanceOf(SoftwareResolver.VersionNotFoundException.class);
    }
}
