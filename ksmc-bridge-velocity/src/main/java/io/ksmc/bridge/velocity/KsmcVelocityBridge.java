package io.ksmc.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import io.ksmc.bridge.BridgeEnv;
import io.ksmc.bridge.KsmcBridge;
import io.ksmc.bridge.Platform;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

/** Bridge Ksmc pour Velocity : enregistrement dynamique des serveurs, routage, joueurs. */
@Plugin(id = "ksmc-bridge", name = "Ksmc Bridge", version = "0.1.0",
        description = "Intégration de Velocity au cloud Ksmc", authors = {"Ksmc"})
public final class KsmcVelocityBridge implements Platform {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer proxy;
    private final Logger logger;
    private KsmcBridge bridge;

    @Inject
    public KsmcVelocityBridge(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        bridge = new KsmcBridge(BridgeEnv.fromSystem(), this);
        bridge.start();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.close();
        }
    }

    // ---- Événements ------------------------------------------------------------------------------

    @Subscribe
    public void onLogin(LoginEvent event) {
        Player p = event.getPlayer();
        if (!bridge.allowedToJoin(p.getUsername(), p::hasPermission)) {
            event.setResult(ResultedEvent.ComponentResult.denied(text(bridge.maintenanceMessage())));
            return;
        }
        String denied = bridge.admitPlayer(p.getUniqueId().toString(), p.getUsername(), p.getProtocolVersion().getProtocol());
        if (denied != null) {
            event.setResult(ResultedEvent.ComponentResult.denied(text(denied)));
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player p = event.getPlayer();
        bridge.playerLogin(p.getUniqueId().toString(), p.getUsername(), p.getProtocolVersion().getProtocol());
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player p = event.getPlayer();
        String host = p.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);
        String target = bridge.initialServer(p::hasPermission, host);
        Optional<RegisteredServer> server = target == null ? Optional.empty() : proxy.getServer(target);
        if (server.isPresent()) {
            event.setInitialServer(server.get());
        } else {
            event.setInitialServer(null);
            p.disconnect(text(bridge.noFallbackMessage()));
        }
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        String from = event.getServer().getServerInfo().getName();
        String target = bridge.fallbackAfterKick(event.getPlayer()::hasPermission, from);
        Optional<RegisteredServer> server = target == null ? Optional.empty() : proxy.getServer(target);
        if (server.isPresent()) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(server.get()));
        } else if (!event.kickedDuringServerConnect()) {
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    event.getServerKickReason().orElse(text(bridge.noFallbackMessage()))));
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player p = event.getPlayer();
        bridge.playerSwitch(p.getUniqueId().toString(), p.getUsername(), event.getServer().getServerInfo().getName());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        bridge.playerDisconnect(event.getPlayer().getUniqueId().toString());
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        ServerPing ping = event.getPing();
        event.setPing(ping.asBuilder()
                .onlinePlayers(bridge.networkPlayers())
                .maximumPlayers(bridge.networkMaxPlayers())
                .build());
    }

    // ---- Platform --------------------------------------------------------------------------------

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public void log(String message) {
        logger.info(message);
    }

    @Override
    public void registerServer(String name, String host, int port) {
        proxy.getServer(name).ifPresent(s -> proxy.unregisterServer(s.getServerInfo()));
        proxy.registerServer(new ServerInfo(name, InetSocketAddress.createUnresolved(host, port)));
        logger.info("[ksmc] serveur ajouté : {} ({}:{})", name, host, port);
    }

    @Override
    public void unregisterServer(String name) {
        proxy.getServer(name).ifPresent(s -> {
            proxy.unregisterServer(s.getServerInfo());
            logger.info("[ksmc] serveur retiré : {}", name);
        });
    }

    @Override
    public List<OnlinePlayer> onlinePlayers() {
        List<OnlinePlayer> out = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            out.add(new OnlinePlayer(p.getUniqueId().toString(), p.getUsername(),
                    p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null),
                    p.getProtocolVersion().getProtocol()));
        }
        return out;
    }

    private Optional<Player> player(String idOrName) {
        try {
            return proxy.getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notUuid) {
            return proxy.getPlayer(idOrName);
        }
    }

    @Override
    public boolean sendPlayer(String player, String server) {
        Optional<Player> p = player(player);
        Optional<RegisteredServer> s = proxy.getServer(server);
        if (p.isEmpty() || s.isEmpty()) {
            return false;
        }
        p.get().createConnectionRequest(s.get()).fireAndForget();
        return true;
    }

    @Override
    public boolean kickPlayer(String player, String reason) {
        return player(player).map(p -> {
            p.disconnect(text(reason));
            return true;
        }).orElse(false);
    }

    @Override
    public boolean messagePlayer(String player, String message) {
        return player(player).map(p -> {
            p.sendMessage(text(message));
            return true;
        }).orElse(false);
    }

    @Override
    public boolean titlePlayer(String player, String title, String subtitle) {
        return player(player).map(p -> {
            p.showTitle(Title.title(text(title), text(subtitle),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))));
            return true;
        }).orElse(false);
    }

    @Override
    public int maxPlayers() {
        return proxy.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public String motd() {
        return LEGACY.serialize(proxy.getConfiguration().getMotd());
    }

    @Override
    public Double tps() {
        return null;
    }

    private static Component text(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy);
    }

    /** Velocity n'a pas de thread principal : les rappels des plugins passent par son ordonnanceur. */
    @Override
    public Executor mainThread() {
        return task -> proxy.getScheduler().buildTask(this, task).schedule();
    }
}
