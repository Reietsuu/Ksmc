package io.ksmc.bridge.bukkit;

import io.ksmc.bridge.BridgeEnv;
import io.ksmc.bridge.KsmcBridge;
import io.ksmc.bridge.Platform;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Bridge Ksmc pour Spigot/Paper (1.8.8 et plus récent). */
public final class KsmcBukkitBridge extends JavaPlugin implements Platform {
    private KsmcBridge bridge;
    private Method paperTps;

    @Override
    public void onEnable() {
        try {
            paperTps = Bukkit.class.getMethod("getTPS");
        } catch (NoSuchMethodException spigot) {
            paperTps = null;
        }
        bridge = new KsmcBridge(BridgeEnv.fromSystem(), this);
        bridge.start();
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            bridge.close();
        }
    }

    /** Exécute sur le thread principal du serveur (API Bukkit non thread-safe) et attend le résultat. */
    private <T> T sync(Callable<T> task, T fallback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(this, task).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Player player(String idOrName) {
        try {
            return Bukkit.getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notUuid) {
            return Bukkit.getPlayerExact(idOrName);
        }
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    @Override
    public String name() {
        return "bukkit";
    }

    @Override
    public void log(String message) {
        getLogger().info(message);
    }

    @Override
    public void registerServer(String name, String host, int port) {
        // Sans objet sur un serveur de jeu.
    }

    @Override
    public void unregisterServer(String name) {
        // Sans objet sur un serveur de jeu.
    }

    @Override
    public List<OnlinePlayer> onlinePlayers() {
        return sync(new Callable<List<OnlinePlayer>>() {
            public List<OnlinePlayer> call() {
                List<OnlinePlayer> out = new ArrayList<OnlinePlayer>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(new OnlinePlayer(p.getUniqueId().toString(), p.getName(), null, 0));
                }
                return out;
            }
        }, new ArrayList<OnlinePlayer>());
    }

    @Override
    public boolean sendPlayer(String player, String server) {
        return false; // L'envoi vers un autre serveur est fait par le proxy.
    }

    @Override
    public boolean kickPlayer(final String player, final String reason) {
        return sync(new Callable<Boolean>() {
            public Boolean call() {
                Player p = player(player);
                if (p == null) {
                    return false;
                }
                p.kickPlayer(color(reason));
                return true;
            }
        }, false);
    }

    @Override
    public boolean messagePlayer(final String player, final String message) {
        return sync(new Callable<Boolean>() {
            public Boolean call() {
                Player p = player(player);
                if (p == null) {
                    return false;
                }
                p.sendMessage(color(message));
                return true;
            }
        }, false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean titlePlayer(final String player, final String title, final String subtitle) {
        return sync(new Callable<Boolean>() {
            public Boolean call() {
                Player p = player(player);
                if (p == null) {
                    return false;
                }
                p.sendTitle(color(title), color(subtitle));
                return true;
            }
        }, false);
    }

    @Override
    public int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public String motd() {
        return Bukkit.getMotd();
    }

    @Override
    public Double tps() {
        if (paperTps == null) {
            return null;
        }
        try {
            double[] tps = (double[]) paperTps.invoke(null);
            return Math.min(20.0, Math.round(tps[0] * 100.0) / 100.0);
        } catch (Exception e) {
            return null;
        }
    }

    private volatile Executor mainThread;

    /** Thread principal (ordonnanceur Bukkit) ; Folia : ordonnanceur de la région globale. */
    @Override
    public Executor mainThread() {
        Executor e = mainThread;
        if (e == null) {
            e = folia();
            if (e == null) {
                e = task -> {
                    if (Bukkit.isPrimaryThread()) {
                        task.run();
                    } else if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this, task);
                    }
                };
            }
            mainThread = e;
        }
        return e;
    }

    /** Ordonnanceur global de Folia (absent de l'API 1.8.8, donc par réflexion), ou {@code null} hors Folia. */
    private Executor folia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            final Object scheduler = org.bukkit.Server.class.getMethod("getGlobalRegionScheduler").invoke(getServer());
            final Method execute = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class);
            return task -> {
                try {
                    execute.invoke(scheduler, this, task);
                } catch (ReflectiveOperationException ex) {
                    getLogger().warning("[ksmc] tâche non planifiée : " + ex);
                }
            };
        } catch (ReflectiveOperationException notFolia) {
            return null;
        }
    }
}
