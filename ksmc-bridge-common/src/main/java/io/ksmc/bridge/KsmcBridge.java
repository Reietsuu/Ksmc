package io.ksmc.bridge;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.Subscription;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Cœur du bridge, commun à toutes les plateformes : connexion au cloud, registre des services,
 * enregistrement dynamique (proxies), routage des joueurs, suivi des joueurs, rapports d'état et actions.
 */
public final class KsmcBridge implements AutoCloseable {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final long REPORT_PERIOD_SECONDS = 5;
    static final long PLAYER_REFRESH_SECONDS = 10;

    private static volatile KsmcBridge instance;

    private final BridgeEnv env;
    private final Platform platform;
    private final ServiceRegistry registry = new ServiceRegistry();
    private final Map<String, String> customProperties = new ConcurrentHashMap<String, String>();
    private final Map<String, PlayerInfo> players = new ConcurrentHashMap<String, PlayerInfo>();
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ksmc-bridge");
            t.setDaemon(true);
            return t;
        }
    });
    private volatile NatsMessaging messaging;
    private volatile String state;
    private volatile String motdOverride;
    private volatile PluginApi pluginApi;
    private volatile boolean closed;

    public KsmcBridge(BridgeEnv env, Platform platform) {
        this.env = env;
        this.platform = platform;
        if (env.isProxy()) {
            registry.addListener(new ServiceRegistry.Listener() {
                public void changed(String name, ServiceInfo previous, ServiceInfo info) {
                    syncServer(name, previous, info);
                }
            });
        }
    }

    /** Instance du bridge du process courant (utilisée par l'API plugins), ou {@code null}. */
    public static KsmcBridge get() {
        return instance;
    }

    public BridgeEnv env() {
        return env;
    }

    public ServiceRegistry registry() {
        return registry;
    }

    public NatsMessaging messaging() {
        return messaging;
    }

    /** Démarre la connexion en arrière-plan (le serveur n'attend jamais le cloud). */
    public void start() {
        instance = this;
        if (!env.isManaged()) {
            platform.log("[ksmc] hors d'un service Ksmc : bridge inactif");
            return;
        }
        pluginApi = new PluginApi(this, platform);
        io.ksmc.plugin.Ksmc.register(pluginApi);
        scheduler.execute(new Runnable() {
            public void run() {
                connect();
            }
        });
    }

    void connect() {
        while (!closed && messaging == null) {
            try {
                Identity id = Identity.service(env.namespace, env.service, env.task, env.environment);
                messaging = NatsMessaging.connect(id, env.natsUrl, env.natsUser, env.natsPassword);
            } catch (Exception e) {
                platform.log("[ksmc] cloud injoignable (" + e.getMessage() + "), nouvel essai dans 5 s");
                sleep(5000);
            }
        }
        if (closed) {
            return;
        }
        subscriptions.add(messaging.keyValue(BridgeSubjects.SERVICES_BUCKET).watchWithCurrent(">",
                new KeyValueApi.Watcher() {
                    public void changed(KeyValueApi.Entry entry) {
                        registry.apply(entry);
                    }
                }));
        subscribePlayerActions();
        if (env.isProxy()) {
            subscribeBroadcasts();
        }
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                report();
            }
        }, 0, REPORT_PERIOD_SECONDS, TimeUnit.SECONDS);
        if (env.isProxy()) {
            scheduler.scheduleAtFixedRate(new Runnable() {
                public void run() {
                    refreshPlayers();
                }
            }, PLAYER_REFRESH_SECONDS, PLAYER_REFRESH_SECONDS, TimeUnit.SECONDS);
        }
        PluginApi api = pluginApi;
        if (api != null) {
            api.connected(messaging);
        }
        platform.log("[ksmc] connecté au cloud (" + env.namespace + "/" + env.service + ")");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Proxies : enregistrement des serveurs ---------------------------------------------------

    void syncServer(String name, ServiceInfo previous, ServiceInfo info) {
        boolean wasRegistered = previous != null && Router.routable(previous);
        boolean shouldRegister = info != null && Router.routable(info);
        if (shouldRegister && (!wasRegistered || !info.host.equals(previous.host) || info.port != previous.port)) {
            if (wasRegistered) {
                platform.unregisterServer(name);
            }
            platform.registerServer(name, info.host, info.port);
        } else if (!shouldRegister && wasRegistered) {
            platform.unregisterServer(name);
        }
    }

    // ---- Proxies : routage -----------------------------------------------------------------------

    /** Serveur initial d'un joueur : forced host si défini, sinon fallback. {@code null} = aucun disponible. */
    public String initialServer(Router.Permissions perms, String virtualHost) {
        String task = Router.forcedHostTask(env.properties(), virtualHost);
        if (task != null) {
            ServiceInfo s = Router.leastFilled(registry.all(), task);
            if (s != null) {
                return s.name;
            }
        }
        ServiceInfo s = Router.fallback(registry.all(), perms, null);
        return s == null ? null : s.name;
    }

    /** Serveur de repli après une expulsion de {@code from}. */
    public String fallbackAfterKick(Router.Permissions perms, String from) {
        ServiceInfo s = Router.fallback(registry.all(), perms, from);
        return s == null ? null : s.name;
    }

    public boolean allowedToJoin(String playerName, Router.Permissions perms) {
        return Router.allowedDuringMaintenance(ownProperties(), playerName, perms);
    }

    /**
     * Propriétés actuelles de ce service : celles du registre (mises à jour à chaud par le controller, ex.
     * maintenance), sinon celles reçues au démarrage.
     */
    Map<String, String> ownProperties() {
        ServiceInfo self = registry.get(env.service);
        return self != null && self.properties != null && !self.properties.isEmpty() ? self.properties : env.properties();
    }

    public String maintenanceMessage() {
        return env.property("ksmc.maintenance.message", "Le réseau est en maintenance.");
    }

    public String noFallbackMessage() {
        return env.property("ksmc.no-fallback.message", "Aucun serveur disponible, réessaie dans un instant.");
    }

    /**
     * Joueurs affichés au ping : tout le réseau, ou les proxies de cette task avec
     * {@code ksmc.player-count.scope: task} (au moins ceux de ce proxy).
     */
    public int networkPlayers() {
        boolean task = "task".equals(ownProperties().get("ksmc.player-count.scope"));
        return Math.max(task ? registry.proxyPlayers(env.task) : registry.networkPlayers(), platform.onlinePlayers().size());
    }

    public int networkMaxPlayers() {
        Integer max = env.manifest.network == null ? null : env.manifest.network.maxPlayers;
        return max != null ? max : platform.maxPlayers();
    }

    // ---- Proxies : joueurs -----------------------------------------------------------------------

    /**
     * Admission d'un joueur (avant {@link #playerLogin}) : prend sa session réseau unique. Renvoie {@code null} si
     * la connexion est acceptée, sinon le message de refus. Cloud injoignable : acceptée (on ne bloque pas le jeu).
     */
    public String admitPlayer(String uuid, String name, int protocolVersion) {
        NatsMessaging m = messaging;
        if (m == null) {
            return null;
        }
        PlayerInfo p = new PlayerInfo();
        p.uuid = uuid;
        p.name = name;
        p.proxy = env.service;
        p.connectedAt = System.currentTimeMillis();
        p.protocolVersion = protocolVersion;
        try {
            SessionLock.Result r = sessionLock(m).acquire(p, ownProperties().get("ksmc.duplicate-login"));
            if (r.outcome == SessionLock.Outcome.ACCEPTED) {
                return null;
            }
            if (r.outcome == SessionLock.Outcome.REPLACE) {
                kickRemote(m, r.holder, r.heldUuid);
                sessionLock(m).takeOver(p);
                return null;
            }
            return duplicateLoginMessage();
        } catch (RuntimeException e) {
            platform.log("[ksmc] session de " + name + " non vérifiée : " + e.getMessage());
            return null;
        }
    }

    private SessionLock sessionLock(NatsMessaging m) {
        return new SessionLock(m.keyValue(BridgeSubjects.PLAYERS_BUCKET), new SessionLock.Liveness() {
            public boolean isRunning(String name) {
                ServiceInfo s = registry.get(name);
                return s != null && "proxy".equals(s.environment) && s.isRunning();
            }
        }, new SessionLock.Directory() {
            public List<PlayerInfo> players() {
                return networkPlayerList();
            }
        });
    }

    /** Expulse la session de {@code uuid} sur le proxy {@code holder} (attente de sa réponse, 3 s au plus). */
    private void kickRemote(NatsMessaging m, String holder, String uuid) {
        PlayerAction kick = PlayerAction.of(PlayerAction.Type.KICK, uuid);
        kick.text = replacedSessionMessage();
        try {
            m.connection().request(BridgeSubjects.playerRpc(env.namespace, holder), Wire.toJson(kick).getBytes(UTF8),
                    java.time.Duration.ofSeconds(3));
        } catch (Exception e) {
            // proxy muet : la session est reprise quand même, il expulsera le joueur à son prochain rafraîchissement
            platform.log("[ksmc] " + holder + " n'a pas expulsé " + uuid + " : " + e.getMessage());
        }
    }

    public String replacedSessionMessage() {
        return env.property("ksmc.duplicate-login.replaced-message", "Tu t'es connecté depuis un autre endroit.");
    }

    public String duplicateLoginMessage() {
        return env.property("ksmc.duplicate-login.message", "Tu es déjà connecté au réseau.");
    }

    public void playerLogin(String uuid, String name, int protocolVersion) {
        PlayerInfo p = new PlayerInfo();
        p.uuid = uuid;
        p.name = name;
        p.proxy = env.service;
        p.connectedAt = System.currentTimeMillis();
        p.protocolVersion = protocolVersion;
        players.put(uuid, p);
        publishPlayer("login", p);
    }

    public void playerSwitch(String uuid, String name, String server) {
        PlayerInfo p = players.get(uuid);
        if (p == null) {
            playerLogin(uuid, name, 0);
            p = players.get(uuid);
        }
        p.server = server;
        publishPlayer("switch", p);
    }

    public void playerDisconnect(String uuid) {
        PlayerInfo p = players.remove(uuid);
        if (p != null) {
            publishPlayer("disconnect", p);
        }
    }

    private void publishPlayer(String type, PlayerInfo p) {
        NatsMessaging m = messaging;
        if (m == null) {
            return;
        }
        try {
            m.connection().publish(BridgeSubjects.playerEvent(env.namespace, type), Wire.toJson(p).getBytes(UTF8));
            KeyValueApi kv = m.keyValue(BridgeSubjects.PLAYERS_BUCKET);
            if ("disconnect".equals(type)) {
                // la session a pu être reprise par un autre proxy (politique replace) : ne pas l'effacer
                if (env.service.equals(sessionLock(m).holder(p.uuid))) {
                    kv.delete(p.uuid);
                }
            } else {
                kv.put(p.uuid, Wire.toJson(p).getBytes(UTF8));
            }
        } catch (RuntimeException e) {
            platform.log("[ksmc] joueur " + p.name + " non publié : " + e.getMessage());
        }
    }

    /** Rafraîchit les entrées des joueurs (TTL côté cloud) : un proxy disparu voit ses joueurs expirer. */
    void refreshPlayers() {
        NatsMessaging m = messaging;
        if (m == null) {
            return;
        }
        try {
            KeyValueApi kv = m.keyValue(BridgeSubjects.PLAYERS_BUCKET);
            SessionLock lock = sessionLock(m);
            for (PlayerInfo p : players.values()) {
                String holder = lock.holder(p.uuid);
                if (holder != null && !holder.equals(env.service) && lock.isLive(holder)) {
                    // session reprise ailleurs sans que l'expulsion nous soit parvenue
                    players.remove(p.uuid);
                    platform.kickPlayer(p.uuid, replacedSessionMessage());
                    continue;
                }
                kv.put(p.uuid, Wire.toJson(p).getBytes(UTF8));
            }
        } catch (RuntimeException e) {
            platform.log("[ksmc] rafraîchissement des joueurs impossible : " + e.getMessage());
        }
    }

    // ---- Rapports d'état -------------------------------------------------------------------------

    public void setState(String state) {
        this.state = state;
        report();
    }

    public String state() {
        return state;
    }

    public void setProperty(String key, String value) {
        if (value == null) {
            customProperties.remove(key);
        } else {
            customProperties.put(key, value);
        }
        report();
    }

    /** Motd annoncé au cloud à la place de celui du serveur ({@code null} : celui du serveur). */
    public void setMotd(String motd) {
        this.motdOverride = motd;
        report();
    }

    public Map<String, String> properties() {
        return new LinkedHashMap<String, String>(customProperties);
    }

    /** Mémoire et GC de la JVM du serveur (le wrapper les expose sur /metrics). */
    static void jvm(ServiceReport r) {
        java.lang.management.MemoryUsage heap = java.lang.management.ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage();
        r.heapUsedBytes = heap.getUsed();
        r.heapMaxBytes = heap.getMax() >= 0 ? heap.getMax() : null;
        long count = 0;
        long time = 0;
        for (java.lang.management.GarbageCollectorMXBean gc
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount());
            time += Math.max(0, gc.getCollectionTime());
        }
        r.gcCount = count;
        r.gcTimeMillis = time;
    }

    void report() {
        NatsMessaging m = messaging;
        if (m == null) {
            return;
        }
        try {
            ServiceReport r = new ServiceReport();
            r.service = env.service;
            r.onlinePlayers = platform.onlinePlayers().size();
            r.maxPlayers = platform.maxPlayers();
            r.motd = motdOverride != null ? motdOverride : platform.motd();
            r.tps = platform.tps();
            jvm(r);
            r.state = state;
            r.properties = new LinkedHashMap<String, String>(customProperties);
            m.connection().publish(BridgeSubjects.report(env.namespace, env.service), Wire.toJson(r).getBytes(UTF8));
        } catch (RuntimeException e) {
            platform.log("[ksmc] rapport non envoyé : " + e.getMessage());
        }
    }

    // ---- Chien de garde -------------------------------------------------------------------------

    /**
     * Chien de garde de ce proxy ({@code ksmc.watchdog.timeout-seconds}, défaut 30), ou {@code null} hors d'un
     * proxy géré ou avec {@code ksmc.watchdog.enabled: false}.
     */
    public Watchdog watchdog() {
        if (!env.isProxy() || !env.isManaged() || "false".equals(env.property("ksmc.watchdog.enabled", "true"))) {
            return null;
        }
        long timeout = 30;
        try {
            timeout = Long.parseLong(env.property("ksmc.watchdog.timeout-seconds", "30"));
        } catch (NumberFormatException ignored) {
            // défaut
        }
        return new Watchdog(Math.max(5, timeout) * 1000, new Watchdog.Clock() {
            public long millis() {
                return System.currentTimeMillis();
            }
        });
    }

    // ---- Arrêt : transfert des joueurs -----------------------------------------------------------

    /**
     * Prépare l'arrêt de ce proxy : attend que le Pod quitte le Service ({@code ksmc.transfer.delay-ms}), puis
     * renvoie pour chaque joueur un transfert ou une expulsion, à exécuter par la plateforme. Les joueurs
     * transférés quittent le registre local sans événement {@code disconnect} (ils restent sur le réseau) et leur
     * session est libérée pour le proxy qui les reçoit.
     */
    public List<TransferPlan.Step> drain(List<TransferPlan.Candidate> candidates) {
        Map<String, String> props = ownProperties();
        boolean other = otherProxyRunning();
        List<TransferPlan.Step> steps = TransferPlan.plan(candidates, other, props);
        boolean transfers = false;
        for (TransferPlan.Step s : steps) {
            transfers |= s.isTransfer();
        }
        if (transfers) {
            long delay = 1500;
            try {
                delay = Long.parseLong(env.property("ksmc.transfer.delay-ms", "1500"));
            } catch (NumberFormatException ignored) {
                // défaut
            }
            sleep(Math.max(0, delay));
        }
        NatsMessaging m = messaging;
        for (TransferPlan.Step s : steps) {
            if (!s.isTransfer()) {
                continue;
            }
            players.remove(s.uuid);
            if (m != null) {
                try {
                    if (env.service.equals(sessionLock(m).holder(s.uuid))) {
                        m.keyValue(BridgeSubjects.PLAYERS_BUCKET).delete(s.uuid);
                    }
                } catch (RuntimeException e) {
                    platform.log("[ksmc] session de " + s.uuid + " non libérée : " + e.getMessage());
                }
            }
        }
        return steps;
    }

    /** Vrai si un autre proxy {@code Running} de la même task peut recevoir les joueurs. */
    boolean otherProxyRunning() {
        for (ServiceInfo s : registry.byTask(env.task)) {
            if (!env.service.equals(s.name) && "proxy".equals(s.environment) && s.isRunning()) {
                return true;
            }
        }
        return false;
    }

    // ---- Commandes réseau ------------------------------------------------------------------------

    /** Commandes réseau de ce proxy ({@code /glist}, {@code /find}, {@code /send}, {@code /alert}). */
    public NetworkCommands commands() {
        return new NetworkCommands(new NetworkCommands.Network() {
            public List<PlayerInfo> players() {
                return networkPlayerList();
            }

            public java.util.Collection<ServiceInfo> services() {
                return registry.all();
            }

            public String act(String proxy, PlayerAction action) {
                if (env.service.equals(proxy)) {
                    return execute(action);
                }
                try {
                    io.nats.client.Message reply = connected().connection().request(
                            BridgeSubjects.playerRpc(env.namespace, proxy), Wire.toJson(action).getBytes(UTF8),
                            java.time.Duration.ofSeconds(5));
                    return reply == null || reply.isStatusMessage() ? "error: proxy " + proxy + " did not answer"
                            : new String(reply.getData(), UTF8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "error: interrupted";
                }
            }

            public void broadcast(String text) {
                KsmcBridge.this.broadcast(text);
            }
        }, env.property("ksmc.alert.prefix", "&c[Annonce] &f"));
    }

    private NatsMessaging connected() {
        NatsMessaging m = messaging;
        if (m == null) {
            throw new IllegalStateException("cloud injoignable");
        }
        return m;
    }

    /** Joueurs en ligne sur tout le réseau (bucket {@code players}). */
    List<PlayerInfo> networkPlayerList() {
        NatsMessaging m = connected();
        List<PlayerInfo> out = new ArrayList<PlayerInfo>();
        try {
            io.nats.client.KeyValue kv = m.connection().keyValue(
                    io.ksmc.messaging.nats.Subjects.bucket(env.namespace, BridgeSubjects.PLAYERS_BUCKET));
            for (String key : kv.keys()) {
                io.nats.client.api.KeyValueEntry e = kv.get(key);
                if (e != null && e.getValue() != null) {
                    out.add(Wire.fromJson(new String(e.getValue(), UTF8), PlayerInfo.class));
                }
            }
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("no keys")) {
                throw new IllegalStateException("registre des joueurs indisponible : " + e.getMessage(), e);
            }
        }
        return out;
    }

    // ---- Annonces réseau -------------------------------------------------------------------------

    /** Annonce {@code text} à tous les joueurs de tous les proxies du namespace (ce proxy compris). */
    public void broadcast(String text) {
        connected().connection().publish(BridgeSubjects.broadcast(env.namespace), text.getBytes(UTF8));
    }

    private void subscribeBroadcasts() {
        final Connection c = messaging.connection();
        final Dispatcher d = c.createDispatcher(msg -> {
            String text = new String(msg.getData(), UTF8);
            for (Platform.OnlinePlayer p : platform.onlinePlayers()) {
                platform.messagePlayer(p.uuid, text);
            }
        });
        d.subscribe(BridgeSubjects.broadcast(env.namespace));
        subscriptions.add(new Subscription() {
            public void close() {
                c.closeDispatcher(d);
            }
        });
    }

    // ---- Actions sur les joueurs -----------------------------------------------------------------

    private void subscribePlayerActions() {
        final Connection c = messaging.connection();
        final Dispatcher d = c.createDispatcher(msg -> {
            PlayerAction action = Wire.fromJson(new String(msg.getData(), UTF8), PlayerAction.class);
            String result = execute(action);
            if (msg.getReplyTo() != null) {
                c.publish(msg.getReplyTo(), result.getBytes(UTF8));
            }
        });
        d.subscribe(BridgeSubjects.playerRpc(env.namespace, env.service));
        subscriptions.add(new Subscription() {
            public void close() {
                c.closeDispatcher(d);
            }
        });
    }

    /** Exécute une action ; renvoie {@code ok} ou {@code error: ...}. */
    String execute(PlayerAction a) {
        boolean ok;
        switch (a.type) {
            case SEND:
                String target = a.targetService;
                if (target == null && a.targetTask != null) {
                    ServiceInfo s = Router.leastFilled(registry.all(), a.targetTask);
                    target = s == null ? null : s.name;
                }
                if (target == null) {
                    return "error: no running service for " + (a.targetTask != null ? "task " + a.targetTask : "target");
                }
                ok = platform.sendPlayer(a.player, target);
                break;
            case KICK:
                ok = platform.kickPlayer(a.player, a.text == null ? "" : a.text);
                break;
            case MESSAGE:
                ok = platform.messagePlayer(a.player, a.text == null ? "" : a.text);
                break;
            case TITLE:
                ok = platform.titlePlayer(a.player, a.text == null ? "" : a.text, a.subtitle == null ? "" : a.subtitle);
                break;
            default:
                return "error: unknown action";
        }
        return ok ? "ok" : "error: player not found: " + a.player;
    }

    @Override
    public void close() {
        closed = true;
        PluginApi api = pluginApi;
        if (api != null) {
            io.ksmc.plugin.Ksmc.unregister(api);
            api.close();
        }
        scheduler.shutdownNow();
        for (Subscription s : subscriptions) {
            try {
                s.close();
            } catch (RuntimeException ignored) {
                // fermeture
            }
        }
        if (messaging != null) {
            messaging.close();
        }
        if (instance == this) {
            instance = null;
        }
    }
}
