package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.manifest.ServiceManifest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigPatcherTest {
    @TempDir
    Path dir;
    Path secretFile;

    @BeforeEach
    void setUp() throws Exception {
        secretFile = Files.writeString(dir.resolve("secret"), "s3cr3t\n");
    }

    ServiceManifest m(String family, String version, String forwarding) {
        ServiceManifest m = new ServiceManifest();
        m.family = family;
        m.softwareVersion = version;
        m.network.forwarding = forwarding;
        m.network.forwardingSecretFile = secretFile.toString();
        return m;
    }

    Path server() throws Exception {
        return Files.createDirectories(dir.resolve("server"));
    }

    String read(Path base, String rel) throws Exception {
        return Files.readString(base.resolve(rel));
    }

    @Test
    void modernPaperUsesPaperGlobalAndDisablesBungeecord() throws Exception {
        Path s = server();
        Files.createDirectories(s.resolve("config"));
        Files.writeString(s.resolve("config/paper-global.yml"), """
                # Paper config
                proxies:
                  bungee-cord:
                    online-mode: true
                  velocity:
                    enabled: false
                    online-mode: false
                    secret: ''
                unrelated:
                  keep: yes
                """);

        new ConfigPatcher().apply(m("paper", "1.21.4", "modern"), s.toFile());

        String global = read(s, "config/paper-global.yml");
        assertThat(global).contains("# Paper config", "    enabled: true", "    online-mode: true", "    secret: 's3cr3t'",
                "  keep: yes", "  bungee-cord:\n    online-mode: true");
        assertThat(read(s, "spigot.yml")).contains("settings:\n  bungeecord: false");
    }

    @Test
    void paper116UsesPaperYmlAndCreatesMissingKeys() throws Exception {
        Path s = server();
        new ConfigPatcher().apply(m("paper", "1.16.5", "modern"), s.toFile());

        assertThat(read(s, "paper.yml")).isEqualTo("""
                settings:
                  velocity-support:
                    enabled: true
                    online-mode: true
                    secret: 's3cr3t'
                """);
    }

    @Test
    void legacySpigotEnablesBungeecord() throws Exception {
        Path s = server();
        Files.writeString(s.resolve("spigot.yml"), "settings:\n  debug: false\n  bungeecord: false\n");
        new ConfigPatcher().apply(m("spigot", "1.8.8", "legacy"), s.toFile());

        assertThat(read(s, "spigot.yml")).isEqualTo("settings:\n  debug: false\n  bungeecord: true\n");
    }

    @Test
    void serverPropertiesPreserveOtherSettings() throws Exception {
        Path s = server();
        Files.writeString(s.resolve("server.properties"), "#comment\nonline-mode=true\nmax-players=50\n");
        new ConfigPatcher().apply(m("forge", "1.20.1", "none"), s.toFile());

        String props = read(s, "server.properties");
        assertThat(props).contains("#comment", "online-mode=false", "max-players=50", "server-port=25565");
        assertThat(props.indexOf("online-mode")).isEqualTo(props.lastIndexOf("online-mode"));
    }

    @Test
    void velocityConfigIsGeneratedWithSecret() throws Exception {
        Path s = server();
        ServiceManifest m = m("velocity", "3.4.0", "modern");
        m.network.maxPlayers = 500;
        m.network.proxyProtocol = true;
        new ConfigPatcher().apply(m, s.toFile());

        String toml = read(s, "velocity.toml");
        assertThat(toml).contains("bind = \"0.0.0.0:25565\"", "player-info-forwarding-mode = \"modern\"",
                "forwarding-secret-file = \"forwarding.secret\"", "show-max-players = 500", "[servers]", "try = []",
                "haproxy-protocol = true");
        assertThat(toml.indexOf("bind")).isLessThan(toml.indexOf("[servers]"));
        assertThat(read(s, "forwarding.secret")).isEqualTo("s3cr3t");
    }

    @Test
    void bungeeConfigIsPatched() throws Exception {
        Path s = server();
        Files.writeString(s.resolve("config.yml"), """
                listeners:
                - query_port: 25577
                  host: 0.0.0.0:25577
                  proxy_protocol: false
                ip_forward: false
                online_mode: false
                """);
        ServiceManifest m = m("bungeecord", "1950", "legacy");
        m.network.proxyProtocol = true;
        new ConfigPatcher().apply(m, s.toFile());

        assertThat(read(s, "config.yml")).contains("  host: 0.0.0.0:25565", "  proxy_protocol: true", "ip_forward: true",
                "online_mode: true");
    }

    @Test
    void fabricModernWritesFabricProxyLiteConfig() throws Exception {
        Path s = server();
        new ConfigPatcher().apply(m("fabric", "1.21.4", "modern"), s.toFile());

        assertThat(read(s, "config/FabricProxy-Lite.toml")).contains("hackOnlineMode = true", "secret = \"s3cr3t\"");
        assertThat(new File(s.toFile(), "spigot.yml")).doesNotExist();
    }
}
