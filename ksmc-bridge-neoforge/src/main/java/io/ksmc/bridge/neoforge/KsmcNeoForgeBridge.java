package io.ksmc.bridge.neoforge;

import io.ksmc.bridge.BridgeEnv;
import io.ksmc.bridge.KsmcBridge;
import io.ksmc.bridge.vanilla.MinecraftPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Bridge Ksmc pour les serveurs NeoForge. */
@Mod(value = "ksmcbridge", dist = Dist.DEDICATED_SERVER)
public final class KsmcNeoForgeBridge {
    private final MinecraftPlatform platform = new MinecraftPlatform("neoforge");
    private final KsmcBridge bridge = new KsmcBridge(BridgeEnv.fromSystem(), platform);

    public KsmcNeoForgeBridge() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            platform.setServer(e.getServer());
            bridge.start();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> bridge.close());
    }
}
