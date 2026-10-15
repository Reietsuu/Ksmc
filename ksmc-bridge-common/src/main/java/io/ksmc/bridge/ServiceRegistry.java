package io.ksmc.bridge;

import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.messaging.KeyValueApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Vue locale des services du namespace, alimentée par le bucket KV {@code services}. */
public final class ServiceRegistry {

    /** Notification d'ajout/mise à jour ({@code info != null}) ou de retrait ({@code info == null}). */
    public interface Listener {
        void changed(String name, ServiceInfo previous, ServiceInfo info);
    }

    private final Map<String, ServiceInfo> services = new ConcurrentHashMap<String, ServiceInfo>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Applique une entrée KV. */
    public void apply(KeyValueApi.Entry entry) {
        if (entry.value == null) {
            remove(entry.key);
        } else {
            update(Wire.fromJson(entry.valueAsString(), ServiceInfo.class));
        }
    }

    public void update(ServiceInfo info) {
        ServiceInfo previous = services.put(info.name, info);
        for (Listener l : listeners) {
            l.changed(info.name, previous, info);
        }
    }

    public void remove(String name) {
        ServiceInfo previous = services.remove(name);
        if (previous != null) {
            for (Listener l : listeners) {
                l.changed(name, previous, null);
            }
        }
    }

    public ServiceInfo get(String name) {
        return services.get(name);
    }

    public Collection<ServiceInfo> all() {
        return new ArrayList<ServiceInfo>(services.values());
    }

    public List<ServiceInfo> byTask(String task) {
        List<ServiceInfo> out = new ArrayList<ServiceInfo>();
        for (ServiceInfo s : services.values()) {
            if (task.equals(s.task)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Joueurs en ligne sur tout le réseau (somme des proxies). */
    public int networkPlayers() {
        int total = 0;
        for (ServiceInfo s : services.values()) {
            if ("proxy".equals(s.environment) && s.isRunning()) {
                total += s.onlinePlayers;
            }
        }
        return total;
    }
}
