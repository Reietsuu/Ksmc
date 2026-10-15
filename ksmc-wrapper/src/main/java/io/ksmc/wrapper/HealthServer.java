package io.ksmc.wrapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Sondes HTTP du service : {@code /live} (process vivant) et {@code /ready} (répond au Server List Ping). */
final class HealthServer {
    private final HttpServer server;
    private volatile long readyCheckedAt;
    private volatile boolean readyCached;
    private volatile String lastStatus;

    HealthServer(int port, final Callable<Boolean> alive, final int minecraftPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/live", new com.sun.net.httpserver.HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                boolean ok;
                try {
                    ok = alive.call();
                } catch (Exception e) {
                    ok = false;
                }
                respond(ex, ok ? 200 : 503, ok ? "alive" : "process not running");
            }
        });
        server.createContext("/ready", new com.sun.net.httpserver.HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                boolean ok = ready(minecraftPort);
                respond(ex, ok ? 200 : 503, ok ? lastStatus : "not ready");
            }
        });
    }

    /** Résultat mis en cache 2 s pour ne pas pinguer le serveur à chaque sonde. */
    boolean ready(int port) {
        long now = System.currentTimeMillis();
        if (now - readyCheckedAt < 2000) {
            return readyCached;
        }
        boolean ok;
        try {
            lastStatus = ServerListPing.ping("127.0.0.1", port, 1500);
            ok = true;
        } catch (IOException e) {
            ok = false;
        }
        readyCached = ok;
        readyCheckedAt = now;
        return ok;
    }

    String lastStatus() {
        return lastStatus;
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(IoUtil.UTF8);
        ex.getResponseHeaders().add("Content-Type", status == 200 && body != null && body.startsWith("{")
                ? "application/json" : "text/plain");
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
