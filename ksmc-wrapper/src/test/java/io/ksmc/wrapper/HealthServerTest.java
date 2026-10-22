package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthServerTest {
    @TempDir
    Path tmp;

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static int status(int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        return c.getResponseCode();
    }

    @Test
    void frozenFlagFailsReadinessThenLivenessAfterGrace() throws Exception {
        int port = freePort();
        HealthServer h = new HealthServer(port, () -> true, freePort());
        FrozenFlag flag = new FrozenFlag(tmp.toFile(), 60_000);
        h.watch(flag);
        h.start();
        try {
            assertThat(status(port, "/live")).isEqualTo(200);

            File file = tmp.resolve(FrozenFlag.PATH).toFile();
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), "bus d'événements sans réponse");
            assertThat(status(port, "/ready")).isEqualTo(503);
            assertThat(status(port, "/live")).isEqualTo(200);

            assertThat(file.setLastModified(System.currentTimeMillis() - 120_000)).isTrue();
            assertThat(status(port, "/live")).isEqualTo(503);

            flag.clear();
            assertThat(file).doesNotExist();
            assertThat(status(port, "/live")).isEqualTo(200);
        } finally {
            h.stop();
        }
    }
}
