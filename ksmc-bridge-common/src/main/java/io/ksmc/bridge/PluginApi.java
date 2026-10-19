package io.ksmc.bridge;

import com.google.gson.JsonParser;
import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.messaging.nats.Subjects;
import io.ksmc.plugin.Channels;
import io.ksmc.plugin.CloudEventInfo;
import io.ksmc.plugin.CloudServiceInfo;
import io.ksmc.plugin.Events;
import io.ksmc.plugin.KeyValue;
import io.ksmc.plugin.KsmcPluginApi;
import io.ksmc.plugin.Message;
import io.ksmc.plugin.PlayerInfo;
import io.ksmc.plugin.ServiceQuery;
import io.ksmc.plugin.ServiceState;
import io.ksmc.plugin.Subscription;
import io.ksmc.plugin.TaskInfo;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.messaging.ChannelApi;
import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.CloudEvent;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.Sender;
import io.ksmc.wire.messaging.Target;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Implémentation de l'API plugins ({@link io.ksmc.plugin.Ksmc#get()}) au-dessus du bridge. */
final class PluginApi implements KsmcPluginApi {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final KsmcBridge bridge;
    private final Platform platform;
    private final Executor main;
    private final ExecutorService io = Executors.newCachedThreadPool(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ksmc-plugin-api");
            t.setDaemon(true);
            return t;
        }
    });
    /** Abonnements des plugins, appliqués à la connexion s'ils ont été demandés avant. */
    private final List<Deferred> subscriptions = new CopyOnWriteArrayList<Deferred>();
    private volatile NatsMessaging messaging;

    PluginApi(KsmcBridge bridge, Platform platform) {
        this.bridge = bridge;
        this.platform = platform;
        this.main = platform.mainThread();
    }

    /** Appelé par le bridge une fois connecté : active les abonnements en attente. */
    void connected(NatsMessaging m) {
        messaging = m;
        for (Deferred d : subscriptions) {
            d.activate(m);
        }
    }

    void close() {
        for (Deferred d : subscriptions) {
            d.close();
        }
        io.shutdownNow();
    }

    private NatsMessaging m() {
        NatsMessaging m = messaging;
        if (m == null) {
            throw new IllegalStateException("cloud Ksmc pas encore connecté");
        }
        return m;
    }

    /** Exécute hors du thread principal et complète le futur sur le thread principal. */
    private <T> CompletableFuture<T> async(final Callable<T> call) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            io.execute(new Runnable() {
                public void run() {
                    try {
                        final T value = call.call();
                        main.execute(new Runnable() {
                            public void run() {
                                f.complete(value);
                            }
                        });
                    } catch (final Throwable t) {
                        main.execute(new Runnable() {
                            public void run() {
                                f.completeExceptionally(t);
                            }
                        });
                    }
                }
            });
        } catch (RuntimeException rejected) {
            f.completeExceptionally(rejected);
        }
        return f;
    }

    // ---- Services, tasks, joueurs ----------------------------------------------------------------

    static CloudServiceInfo info(ServiceInfo s) {
        return new CloudServiceInfo(s.name, s.task, s.environment, s.phase, s.host, s.port, s.onlinePlayers,
                s.maxPlayers, s.motd, s.state, s.tps, s.fallback, s.maintenance, s.properties);
    }

    @Override
    public CloudServiceInfo currentService() {
        BridgeEnv env = bridge.env();
        ServiceInfo s = bridge.registry().get(env.service);
        if (s != null) {
            return info(s);
        }
        // Pas encore publié par le controller : état local.
        return new CloudServiceInfo(env.service, env.task, env.environment, "Starting", null, 0,
                platform.onlinePlayers().size(), platform.maxPlayers(), platform.motd(), bridge.state(), platform.tps(),
                false, false, bridge.properties());
    }

    @Override
    public List<CloudServiceInfo> services() {
        return services(ServiceQuery.all());
    }

    @Override
    public List<CloudServiceInfo> services(ServiceQuery query) {
        List<CloudServiceInfo> out = new ArrayList<CloudServiceInfo>();
        for (ServiceInfo s : bridge.registry().all()) {
            CloudServiceInfo i = info(s);
            if (query.matches(i)) {
                out.add(i);
            }
        }
        Collections.sort(out, new Comparator<CloudServiceInfo>() {
            public int compare(CloudServiceInfo a, CloudServiceInfo b) {
                return a.name().compareTo(b.name());
            }
        });
        return out;
    }

    @Override
    public CloudServiceInfo service(String name) {
        ServiceInfo s = bridge.registry().get(name);
        return s == null ? null : info(s);
    }

    @Override
    public List<TaskInfo> tasks() {
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        Map<String, String> environments = new LinkedHashMap<String, String>();
        for (CloudServiceInfo s : services()) {
            if (s.task() == null) {
                continue;
            }
            int[] c = counts.get(s.task());
            if (c == null) {
                c = new int[3];
                counts.put(s.task(), c);
                environments.put(s.task(), s.environment());
            }
            c[0]++;
            c[1] += s.isRunning() ? 1 : 0;
            c[2] += s.onlinePlayers();
        }
        List<TaskInfo> out = new ArrayList<TaskInfo>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            out.add(new TaskInfo(e.getKey(), environments.get(e.getKey()), e.getValue()[0], e.getValue()[1],
                    e.getValue()[2]));
        }
        return out;
    }

    static PlayerInfo player(io.ksmc.wire.bridge.PlayerInfo p) {
        return new PlayerInfo(p.uuid, p.name, p.proxy, p.server, p.connectedAt);
    }

    @Override
    public CompletableFuture<List<PlayerInfo>> players() {
        return async(new Callable<List<PlayerInfo>>() {
            public List<PlayerInfo> call() throws Exception {
                return listPlayers();
            }
        });
    }

    List<PlayerInfo> listPlayers() throws Exception {
        NatsMessaging m = m();
        io.nats.client.KeyValue kv = m.connection().keyValue(
                Subjects.bucket(m.identity().namespace, BridgeSubjects.PLAYERS_BUCKET));
        List<PlayerInfo> out = new ArrayList<PlayerInfo>();
        List<String> keys;
        try {
            keys = kv.keys();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("no keys")) {
                return out;
            }
            throw e;
        }
        for (String key : keys) {
            io.nats.client.api.KeyValueEntry e = kv.get(key);
            if (e != null && e.getValue() != null) {
                out.add(player(Wire.fromJson(new String(e.getValue(), UTF8), io.ksmc.wire.bridge.PlayerInfo.class)));
            }
        }
        Collections.sort(out, new Comparator<PlayerInfo>() {
            public int compare(PlayerInfo a, PlayerInfo b) {
                return a.name().compareToIgnoreCase(b.name());
            }
        });
        return out;
    }

    @Override
    public CompletableFuture<PlayerInfo> player(final String nameOrUuid) {
        return async(new Callable<PlayerInfo>() {
            public PlayerInfo call() throws Exception {
                KeyValueApi.Entry e = m().keyValue(BridgeSubjects.PLAYERS_BUCKET).get(nameOrUuid);
                if (e != null && e.value != null) {
                    return player(Wire.fromJson(e.valueAsString(), io.ksmc.wire.bridge.PlayerInfo.class));
                }
                for (PlayerInfo p : listPlayers()) {
                    if (p.name().equalsIgnoreCase(nameOrUuid)) {
                        return p;
                    }
                }
                return null;
            }
        });
    }

    // ---- Channels --------------------------------------------------------------------------------

    static ChannelMessage wire(String target, String channel, String message, String data) {
        ChannelMessage cm = ChannelMessage.of(channel, message).target(target == null ? Target.all() : Target.parse(target));
        if (data != null) {
            cm.data = JsonParser.parseString(data);
        }
        return cm;
    }

    static Message message(ChannelMessage cm) {
        String sender = cm.sender != null && cm.sender.type == Sender.Type.SERVICE ? cm.sender.name : null;
        return new Message(cm.channel, cm.message, cm.data == null || cm.data.isJsonNull() ? null : cm.data.toString(),
                sender);
    }

    @Override
    public Channels channels() {
        return new Channels() {
            public void send(String target, String channel, String message) {
                send(target, channel, message, null);
            }

            public void send(String target, String channel, String message, String data) {
                m().channels().send(wire(target, channel, message, data));
            }

            public CompletableFuture<Message> request(String target, String channel, String message, String data,
                    long timeoutMillis) {
                final CompletableFuture<Message> f = new CompletableFuture<Message>();
                try {
                    m().channels().request(wire(target, channel, message, data), timeoutMillis)
                            .whenComplete((reply, error) -> main.execute(new Runnable() {
                                public void run() {
                                    if (error != null) {
                                        f.completeExceptionally(error);
                                    } else {
                                        f.complete(message(reply));
                                    }
                                }
                            }));
                } catch (RuntimeException e) {
                    f.completeExceptionally(e);
                }
                return f;
            }

            public Subscription subscribe(final String channel, final Handler handler) {
                return defer(new Activation() {
                    public io.ksmc.wire.messaging.Subscription activate(NatsMessaging m) {
                        return m.channels().subscribe(channel, new ChannelApi.Handler() {
                            public ChannelMessage handle(ChannelMessage in) {
                                Message reply = handler.handle(message(in));
                                if (reply == null) {
                                    return null;
                                }
                                ChannelMessage out = in.reply(reply.message());
                                if (reply.data() != null) {
                                    out.data = JsonParser.parseString(reply.data());
                                }
                                return out;
                            }
                        });
                    }
                });
            }
        };
    }

    // ---- Événements et KV ------------------------------------------------------------------------

    @Override
    public Events events() {
        return new Events() {
            public Subscription subscribe(final String typePattern, final Listener listener) {
                return defer(new Activation() {
                    public io.ksmc.wire.messaging.Subscription activate(NatsMessaging m) {
                        return m.events().subscribe(EventFilter.type(typePattern), (CloudEvent e) -> {
                            final CloudEventInfo info = new CloudEventInfo(e.type, e.subject, e.task, e.environment,
                                    e.data == null || e.data.isJsonNull() ? null : e.data.toString(), e.timestamp);
                            main.execute(new Runnable() {
                                public void run() {
                                    listener.onEvent(info);
                                }
                            });
                        });
                    }
                });
            }
        };
    }

    @Override
    public KeyValue keyValue(final String bucket) {
        return new KeyValue() {
            public CompletableFuture<String> get(final String key) {
                return async(new Callable<String>() {
                    public String call() {
                        KeyValueApi.Entry e = m().keyValue(bucket).get(key);
                        return e == null ? null : e.valueAsString();
                    }
                });
            }

            public CompletableFuture<Void> put(final String key, final String value) {
                return async(new Callable<Void>() {
                    public Void call() {
                        m().keyValue(bucket).put(key, value.getBytes(UTF8));
                        return null;
                    }
                });
            }

            public CompletableFuture<Void> delete(final String key) {
                return async(new Callable<Void>() {
                    public Void call() {
                        m().keyValue(bucket).delete(key);
                        return null;
                    }
                });
            }

            public Subscription watch(final String keyPattern, final Watcher watcher) {
                return defer(new Activation() {
                    public io.ksmc.wire.messaging.Subscription activate(NatsMessaging m) {
                        return m.keyValue(bucket).watchWithCurrent(keyPattern, new KeyValueApi.Watcher() {
                            public void changed(final KeyValueApi.Entry entry) {
                                main.execute(new Runnable() {
                                    public void run() {
                                        watcher.changed(entry.key, entry.valueAsString());
                                    }
                                });
                            }
                        });
                    }
                });
            }
        };
    }

    // ---- État du service -------------------------------------------------------------------------

    @Override
    public ServiceState state() {
        return new ServiceState() {
            public void setState(String state) {
                bridge.setState(state);
            }

            public String state() {
                return bridge.state();
            }

            public void setMotd(String motd) {
                bridge.setMotd(motd);
            }

            public void setProperty(String key, String value) {
                bridge.setProperty(key, value);
            }

            public Map<String, String> properties() {
                return Collections.unmodifiableMap(bridge.properties());
            }
        };
    }

    @Override
    public boolean isConnected() {
        NatsMessaging m = messaging;
        return m != null && m.isConnected();
    }

    @Override
    public Executor mainThread() {
        return main;
    }

    // ---- Abonnements différés --------------------------------------------------------------------

    interface Activation {
        io.ksmc.wire.messaging.Subscription activate(NatsMessaging m);
    }

    private Subscription defer(Activation activation) {
        Deferred d = new Deferred(activation);
        subscriptions.add(d);
        NatsMessaging m = messaging;
        if (m != null) {
            d.activate(m);
        }
        return d;
    }

    private final class Deferred implements Subscription {
        private final Activation activation;
        private io.ksmc.wire.messaging.Subscription active;
        private boolean closed;

        Deferred(Activation activation) {
            this.activation = activation;
        }

        synchronized void activate(NatsMessaging m) {
            if (!closed && active == null) {
                try {
                    active = activation.activate(m);
                } catch (RuntimeException e) {
                    platform.log("[ksmc] abonnement d'un plugin impossible : " + e.getMessage());
                }
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            subscriptions.remove(this);
            if (active != null) {
                try {
                    active.close();
                } catch (RuntimeException ignored) {
                    // déjà fermé
                }
                active = null;
            }
        }
    }
}
