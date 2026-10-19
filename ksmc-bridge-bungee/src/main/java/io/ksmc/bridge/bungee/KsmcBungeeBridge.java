package io.ksmc.bridge.bungee;

import io.ksmc.bridge.BridgeEnv;
import io.ksmc.bridge.KsmcBridge;
import io.ksmc.bridge.Platform;
import io.ksmc.bridge.Router;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/** Bridge Ksmc pour BungeeCord et Waterfall. */
public final class KsmcBungeeBridge extends Plugin implements Listener, Platform {
    private KsmcBridge bridge;

    @Override
    public void onEnable() {
        bridge = new KsmcBridge(BridgeEnv.fromSystem(), this);
        getProxy().getPluginManager().registerListener(this, this);
        bridge.start();
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.close();
        }
    }

    private static Router.Permissions perms(final ProxiedPlayer p) {
        return new Router.Permissions() {
            public boolean has(String permission) {
                return p.hasPermission(permission);
            }
        };
    }

    // ---- Événements ------------------------------------------------------------------------------

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer p = event.getPlayer();
        if (!bridge.allowedToJoin(p.getName(), perms(p))) {
            p.disconnect(text(bridge.maintenanceMessage()));
            return;
        }
        bridge.playerLogin(p.getUniqueId().toString(), p.getName(), p.getPendingConnection().getVersion());
    }

    @EventHandler
    public void onConnect(ServerConnectEvent event) {
        if (event.getReason() != ServerConnectEvent.Reason.JOIN_PROXY) {
            return;
        }
        ProxiedPlayer p = event.getPlayer();
        InetSocketAddress host = p.getPendingConnection().getVirtualHost();
        String target = bridge.initialServer(perms(p), host == null ? null : host.getHostString());
        ServerInfo server = target == null ? null : getProxy().getServerInfo(target);
        if (server != null) {
            event.setTarget(server);
        } else {
            event.setCancelled(true);
            p.disconnect(text(bridge.noFallbackMessage()));
        }
    }

    @EventHandler
    public void onKick(ServerKickEvent event) {
        String target = bridge.fallbackAfterKick(perms(event.getPlayer()), event.getKickedFrom().getName());
        ServerInfo server = target == null ? null : getProxy().getServerInfo(target);
        if (server != null) {
            event.setCancelServer(server);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwitch(ServerSwitchEvent event) {
        ProxiedPlayer p = event.getPlayer();
        bridge.playerSwitch(p.getUniqueId().toString(), p.getName(), p.getServer().getInfo().getName());
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        bridge.playerDisconnect(event.getPlayer().getUniqueId().toString());
    }

    @EventHandler
    public void onPing(ProxyPingEvent event) {
        if (event.getResponse() != null && event.getResponse().getPlayers() != null) {
            event.getResponse().getPlayers().setOnline(bridge.networkPlayers());
            event.getResponse().getPlayers().setMax(bridge.networkMaxPlayers());
        }
    }

    // ---- Platform --------------------------------------------------------------------------------

    @Override
    public String name() {
        return "bungeecord";
    }

    @Override
    public void log(String message) {
        getLogger().info(message);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void registerServer(String name, String host, int port) {
        ServerInfo info = getProxy().constructServerInfo(name, InetSocketAddress.createUnresolved(host, port), name, false);
        getProxy().getServers().put(name, info);
        getLogger().info("[ksmc] serveur ajouté : " + name + " (" + host + ":" + port + ")");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void unregisterServer(String name) {
        if (getProxy().getServers().remove(name) != null) {
            getLogger().info("[ksmc] serveur retiré : " + name);
        }
    }

    @Override
    public List<OnlinePlayer> onlinePlayers() {
        List<OnlinePlayer> out = new ArrayList<OnlinePlayer>();
        for (ProxiedPlayer p : getProxy().getPlayers()) {
            out.add(new OnlinePlayer(p.getUniqueId().toString(), p.getName(),
                    p.getServer() == null ? null : p.getServer().getInfo().getName(), p.getPendingConnection().getVersion()));
        }
        return out;
    }

    private ProxiedPlayer player(String idOrName) {
        try {
            return getProxy().getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notUuid) {
            return getProxy().getPlayer(idOrName);
        }
    }

    @Override
    public boolean sendPlayer(String player, String server) {
        ProxiedPlayer p = player(player);
        ServerInfo s = getProxy().getServerInfo(server);
        if (p == null || s == null) {
            return false;
        }
        p.connect(s);
        return true;
    }

    @Override
    public boolean kickPlayer(String player, String reason) {
        ProxiedPlayer p = player(player);
        if (p == null) {
            return false;
        }
        p.disconnect(text(reason));
        return true;
    }

    @Override
    public boolean messagePlayer(String player, String message) {
        ProxiedPlayer p = player(player);
        if (p == null) {
            return false;
        }
        p.sendMessage(text(message));
        return true;
    }

    @Override
    public boolean titlePlayer(String player, String title, String subtitle) {
        ProxiedPlayer p = player(player);
        if (p == null) {
            return false;
        }
        ProxyServer.getInstance().createTitle().title(text(title)).subTitle(text(subtitle))
                .fadeIn(10).stay(60).fadeOut(10).send(p);
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int maxPlayers() {
        return getProxy().getConfig().getPlayerLimit() > 0 ? getProxy().getConfig().getPlayerLimit()
                : getProxy().getConfig().getListeners().iterator().next().getMaxPlayers();
    }

    @Override
    public String motd() {
        return getProxy().getConfig().getListeners().iterator().next().getMotd();
    }

    @Override
    public Double tps() {
        return null;
    }

    @SuppressWarnings("deprecation")
    private static BaseComponent[] text(String legacy) {
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', legacy == null ? "" : legacy));
    }
}
