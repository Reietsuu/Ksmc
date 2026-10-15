package io.ksmc.controller.service;

import io.ksmc.wire.manifest.ServiceManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Choix du JAR de bridge adapté à la famille d'un logiciel. */
public final class BridgeArtifacts {
    /** Famille → (fichier du bridge, dossier cible dans le service). */
    static final Map<String, String[]> BY_FAMILY = Map.of(
            "velocity", new String[] {"ksmc-bridge-velocity.jar", "plugins"},
            "bungeecord", new String[] {"ksmc-bridge-bungee.jar", "plugins"},
            "paper", new String[] {"ksmc-bridge-bukkit.jar", "plugins"},
            "spigot", new String[] {"ksmc-bridge-bukkit.jar", "plugins"},
            "fabric", new String[] {"ksmc-bridge-fabric.jar", "mods"},
            "neoforge", new String[] {"ksmc-bridge-neoforge.jar", "mods"});

    private final String bridgesDir;

    public BridgeArtifacts(String bridgesDir) {
        this.bridgesDir = bridgesDir;
    }

    /** Artefact du bridge pour cette famille, si un bridge existe et est présent dans l'image du controller. */
    public Optional<ServiceManifest.Artifact> forFamily(String family) {
        String[] entry = BY_FAMILY.get(family);
        if (entry == null || !Files.isRegularFile(Path.of(bridgesDir, entry[0]))) {
            return Optional.empty();
        }
        ServiceManifest.Artifact a = new ServiceManifest.Artifact();
        a.url = "/internal/v1/bridges/" + entry[0];
        a.target = entry[1] + "/ksmc-bridge.jar";
        return Optional.of(a);
    }
}
