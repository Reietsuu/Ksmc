package io.ksmc.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Plateforme simulée enregistrant les appels du bridge. */
final class FakePlatform implements Platform {
    final Map<String, String> servers = new ConcurrentHashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<OnlinePlayer> players = new CopyOnWriteArrayList<>();

    public String name() {
        return "fake";
    }

    public void log(String message) {
    }

    public void registerServer(String name, String host, int port) {
        servers.put(name, host + ":" + port);
    }

    public void unregisterServer(String name) {
        servers.remove(name);
    }

    public List<OnlinePlayer> onlinePlayers() {
        return new ArrayList<>(players);
    }

    public boolean sendPlayer(String player, String server) {
        calls.add("send " + player + " " + server);
        return true;
    }

    public boolean kickPlayer(String player, String reason) {
        calls.add("kick " + player + " " + reason);
        return player.equals("Steve");
    }

    public boolean messagePlayer(String player, String message) {
        calls.add("message " + player + " " + message);
        return true;
    }

    public boolean titlePlayer(String player, String title, String subtitle) {
        calls.add("title " + player + " " + title + "/" + subtitle);
        return true;
    }

    public int maxPlayers() {
        return 20;
    }

    public String motd() {
        return "A Minecraft Server";
    }

    public Double tps() {
        return 19.9;
    }
}
