package io.ksmc.bridge.vanilla;

import io.ksmc.bridge.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link Platform} pour un serveur Minecraft moddé (Fabric, NeoForge) via les classes officielles. */
public final class MinecraftPlatform implements Platform {
    private static final Logger LOG = LoggerFactory.getLogger("ksmc-bridge");

    private final String loader;
    private volatile MinecraftServer server;

    public MinecraftPlatform(String loader) {
        this.loader = loader;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /** Exécute sur le thread du serveur et attend le résultat. */
    private <T> T onServer(Supplier<T> task, T fallback) {
        MinecraftServer s = server;
        if (s == null) {
            return fallback;
        }
        if (s.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        s.execute(() -> {
            try {
                f.complete(task.get());
            } catch (RuntimeException e) {
                f.complete(fallback);
            }
        });
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private ServerPlayer player(String idOrName) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notUuid) {
            return server.getPlayerList().getPlayerByName(idOrName);
        }
    }

    @Override
    public String name() {
        return loader;
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }

    @Override
    public void registerServer(String name, String host, int port) {
    }

    @Override
    public void unregisterServer(String name) {
    }

    @Override
    public List<OnlinePlayer> onlinePlayers() {
        return onServer(() -> {
            List<OnlinePlayer> out = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                out.add(new OnlinePlayer(p.getUUID().toString(), p.getGameProfile().name(), null, 0));
            }
            return out;
        }, new ArrayList<>());
    }

    @Override
    public boolean sendPlayer(String player, String target) {
        return false;
    }

    @Override
    public boolean kickPlayer(String player, String reason) {
        return onServer(() -> {
            ServerPlayer p = player(player);
            if (p == null) {
                return false;
            }
            p.connection.disconnect(Component.literal(reason));
            return true;
        }, false);
    }

    @Override
    public boolean messagePlayer(String player, String message) {
        return onServer(() -> {
            ServerPlayer p = player(player);
            if (p == null) {
                return false;
            }
            p.sendSystemMessage(Component.literal(message));
            return true;
        }, false);
    }

    @Override
    public boolean titlePlayer(String player, String title, String subtitle) {
        return onServer(() -> {
            ServerPlayer p = player(player);
            if (p == null) {
                return false;
            }
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
            return true;
        }, false);
    }

    @Override
    public int maxPlayers() {
        MinecraftServer s = server;
        return s == null ? 0 : s.getPlayerList().getMaxPlayers();
    }

    @Override
    public String motd() {
        MinecraftServer s = server;
        return s == null ? null : s.getMotd();
    }

    @Override
    public Double tps() {
        MinecraftServer s = server;
        if (s == null) {
            return null;
        }
        long nanos = s.getAverageTickTimeNanos();
        return nanos <= 0 ? 20.0 : Math.min(20.0, Math.round(1_000_000_000.0 / nanos * 100.0) / 100.0);
    }

    /** Thread du serveur Minecraft (file de tâches du serveur) ; sur place tant que le serveur n'est pas démarré. */
    @Override
    public Executor mainThread() {
        return task -> {
            MinecraftServer s = server;
            if (s == null || s.isSameThread()) {
                task.run();
            } else {
                s.execute(task);
            }
        };
    }
}
