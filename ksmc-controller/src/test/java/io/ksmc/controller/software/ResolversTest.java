package io.ksmc.controller.software;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ArtifactPlan;
import io.ksmc.api.spi.ResolvedSoftware;
import io.ksmc.api.spi.SoftwareResolver;
import org.junit.jupiter.api.Test;

class ResolversTest {
    static final String API = "https://fill.papermc.io/v3/projects/";

    static SoftwareDefinition def(String name, String resolverYaml) {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: SoftwareDefinition
                metadata: {name: %s}
                spec:
                  environment: server
                  family: paper
                  resolver: %s
                """.formatted(name, resolverYaml), SoftwareDefinition.class);
    }

    static String build(int id, String channel, String file) {
        return """
                {"id":%d,"channel":"%s","downloads":{"server:default":{"name":"%s",
                "checksums":{"sha256":"abc%d"},"size":1,"url":"https://fill-data.papermc.io/v1/objects/x/%s"}}}
                """.formatted(id, channel, file, id, file);
    }

    final FakeResolverContext paper = new FakeResolverContext()
            .on(API + "paper", """
                    {"project":{"id":"paper"},"versions":{"26.3":["26.3","26.3-rc-3"],"1.21":["1.21.11","1.21.4"],"1.8":["1.8.8"]}}
                    """)
            .on(API + "paper/versions/26.3/builds/latest", build(5, "BETA", "paper-26.3-5.jar"))
            .on(API + "paper/versions/1.21.11/builds/latest", build(130, "STABLE", "paper-1.21.11-130.jar"))
            .on(API + "paper/versions/1.21.4/builds/latest", build(232, "STABLE", "paper-1.21.4-232.jar"))
            .on(API + "paper/versions/1.21.4/builds/200", build(200, "STABLE", "paper-1.21.4-200.jar"))
            .on(API + "paper/versions/1.21.4", "{\"version\":{\"id\":\"1.21.4\",\"java\":{\"version\":{\"minimum\":21}}}}");

    final PaperMcResolver resolver = new PaperMcResolver();
    final SoftwareDefinition paperDef = def("paper", "{type: papermc, params: {project: paper}}");

    @Test
    void listsVersionsNewestFirst() throws Exception {
        assertThat(resolver.versions(paperDef, paper)).containsExactly("26.3", "26.3-rc-3", "1.21.11", "1.21.4", "1.8.8");
    }

    @Test
    void latestSkipsNonStableBuilds() throws Exception {
        ResolvedSoftware r = resolver.resolve(paperDef, "latest", paper);
        assertThat(r.version()).isEqualTo("1.21.11");
        assertThat(r.build()).isEqualTo("130");
    }

    @Test
    void exactVersionUsesLatestBuildChecksumAndJavaHint() throws Exception {
        ResolvedSoftware r = resolver.resolve(paperDef, "1.21.4", paper);
        ArtifactPlan.Download d = (ArtifactPlan.Download) r.plan();

        assertThat(r.build()).isEqualTo("232");
        assertThat(r.javaVersion()).isEqualTo(21);
        assertThat(d.sha256()).isEqualTo("abc232");
        assertThat(d.url()).endsWith("paper-1.21.4-232.jar");
        assertThat(d.target()).isEqualTo("server.jar");
        assertThat(d.cacheKey()).isEqualTo("papermc/paper/1.21.4/232/server.jar");
    }

    @Test
    void pinnedBuild() throws Exception {
        assertThat(resolver.resolve(paperDef, "1.21.4@200", paper).build()).isEqualTo("200");
    }

    @Test
    void unknownVersionIsReportedAsNotFound() {
        assertThatThrownBy(() -> resolver.resolve(paperDef, "0.0.1", paper))
                .isInstanceOf(SoftwareResolver.VersionNotFoundException.class);
    }

    @Test
    void urlResolverSubstitutesVersion() throws Exception {
        SoftwareDefinition d = def("mypaper",
                "{type: url, params: {url: 'https://example.org/{version}/server.jar', versions: '2.0, 1.0'}}");
        UrlResolver url = new UrlResolver();

        ResolvedSoftware exact = url.resolve(d, "1.21.4", new FakeResolverContext());
        assertThat(((ArtifactPlan.Download) exact.plan()).url()).isEqualTo("https://example.org/1.21.4/server.jar");
        assertThat(url.resolve(d, "latest", new FakeResolverContext()).version()).isEqualTo("2.0");
    }
}
