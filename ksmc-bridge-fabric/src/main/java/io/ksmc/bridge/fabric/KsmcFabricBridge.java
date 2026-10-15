package io.ksmc.bridge.fabric;

import io.ksmc.bridge.BridgeEnv;
import io.ksmc.bridge.KsmcBridge;
import io.ksmc.bridge.vanilla.MinecraftPlatform;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Bridge Ksmc pour les serveurs Fabric. */
public final class KsmcFabricBridge implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        MinecraftPlatform platform = new MinecraftPlatform("fabric");
        KsmcBridge bridge = new KsmcBridge(BridgeEnv.fromSystem(), platform);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            platform.setServer(server);
            bridge.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> bridge.close());
    }
}
