package io.ksmc.wrapper;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Faux controller : sert des réponses fixes par chemin et enregistre les PUT. */
final class FakeControllerServer implements AutoCloseable {
    final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
    final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakeControllerServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authHeaders.put(path, auth);
            }
            if (ex.getRequestMethod().equals("PUT")) {
                uploads.put(path, ex.getRequestBody().readAllBytes());
                ex.sendResponseHeaders(204, -1);
            } else {
                byte[] body = responses.get(path);
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                }
            }
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(IoUtil.UTF8));
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
