package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeArtifactsTest {
    @TempDir
    Path dir;

    @Test
    void picksPlatformJarAndTargetFolder() throws Exception {
        Files.writeString(dir.resolve("ksmc-bridge-bukkit.jar"), "x");
        Files.writeString(dir.resolve("ksmc-bridge-velocity.jar"), "x");
        BridgeArtifacts b = new BridgeArtifacts(dir.toString());

        assertThat(b.forFamily("paper")).get().satisfies(a -> {
            assertThat(a.url).isEqualTo("/internal/v1/bridges/ksmc-bridge-bukkit.jar");
            assertThat(a.target).isEqualTo("plugins/ksmc-bridge.jar");
        });
        assertThat(b.forFamily("spigot")).isPresent();
        assertThat(b.forFamily("velocity")).get().extracting(a -> a.target).isEqualTo("plugins/ksmc-bridge.jar");
        assertThat(b.forFamily("bungeecord")).as("jar absent de l'image").isEmpty();
        assertThat(b.forFamily("forge")).as("pas de bridge Forge legacy").isEmpty();

        Files.writeString(dir.resolve("ksmc-bridge-fabric.jar"), "x");
        assertThat(b.forFamily("fabric", "26.3")).get().extracting(a -> a.target).isEqualTo("mods/ksmc-bridge.jar");
        assertThat(b.forFamily("fabric", "1.21.4")).as("hors de la ligne 26.x").isEmpty();
    }
}
