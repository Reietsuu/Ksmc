package io.ksmc.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinecraftVersionsTest {

    @Test
    void comparesNumericallyIncludingYearBasedVersions() {
        assertThat(MinecraftVersions.compare("1.21.11", "1.21.4")).isPositive();
        assertThat(MinecraftVersions.compare("26.1", "1.21.11")).isPositive();
        assertThat(MinecraftVersions.compare("1.20", "1.20.0")).isZero();
        assertThat(MinecraftVersions.compare("1.21.11-rc3", "1.21.11")).isZero();
    }

    @Test
    void evaluatesRanges() {
        assertThat(MinecraftVersions.inRange("1.8.8", "1.8-1.16.5")).isTrue();
        assertThat(MinecraftVersions.inRange("1.16.5", "1.8-1.16.5")).isTrue();
        assertThat(MinecraftVersions.inRange("1.17", "1.8-1.16.5")).isFalse();
        assertThat(MinecraftVersions.inRange("26.1.2", "1.20.5+")).isTrue();
        assertThat(MinecraftVersions.inRange("1.20.4", "1.20.4")).isTrue();
    }

    @Test
    void picksJavaVersionFromDefaultRules() {
        SoftwareDefinitionSpec spec = Serialization.unmarshal("""
                environment: server
                family: paper
                resolver: {type: papermc}
                javaVersions:
                  - {minecraft: "1.8-1.16.5", java: 8}
                  - {minecraft: "1.17-1.20.4", java: 17}
                  - {minecraft: "1.20.5-1.21.11", java: 21}
                  - {minecraft: "26+", java: 25}
                """, SoftwareDefinitionSpec.class);
        List<SoftwareDefinitionSpec.JavaVersionRule> rules = spec.javaVersions;

        assertThat(MinecraftVersions.javaFor("1.8.8", rules, 21)).isEqualTo(8);
        assertThat(MinecraftVersions.javaFor("1.20.4", rules, 21)).isEqualTo(17);
        assertThat(MinecraftVersions.javaFor("1.20.6", rules, 21)).isEqualTo(21);
        assertThat(MinecraftVersions.javaFor("26.1.2", rules, 21)).isEqualTo(25);
        assertThat(MinecraftVersions.javaFor("unknown", List.of(), 21)).isEqualTo(21);
    }
}
