package io.ksmc.wrapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.Wire;
import io.ksmc.wire.manifest.ServiceManifest;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {
    @TempDir
    Path tmp;

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    Runner runner(int mcPort, int healthPort, String... extraArgs) throws Exception {
        ServiceManifest m = new ServiceManifest();
        m.network.port = mcPort;
        m.launch.args.addAll(Arrays.asList("-cp", System.getProperty("java.class.path"),
                FakeMinecraftServer.class.getName(), String.valueOf(mcPort)));
        m.launch.args.addAll(Arrays.asList(extraArgs));
        m.launch.stopTimeoutSeconds = 2;
        WrapperEnv env = new WrapperEnv("http://127.0.0.1:1", tmp.resolve("token").toFile(), "test", "s",
                tmp.toFile(), healthPort, null);
        Files.createDirectories(env.stateDir().toPath());
        Files.writeString(env.manifestFile().toPath(), Wire.toJson(m));
        return new Runner(env);
    }

    static int status(int port, String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;
        }
    }

    static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 15000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timeout");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void becomesReadyAndStopsOnStopCommand() throws Exception {
        int mc = freePort();
        int health = freePort();
        Runner runner = runner(mc, health);
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        await(() -> status(health, "/ready") == 200);
        assertThat(status(health, "/live")).isEqualTo(200);

        runner.sendCommand("stop");
        assertThat(exit.get(15, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void gracefulStopForcesKillAfterTimeout() throws Exception {
        int mc = freePort();
        int health = freePort();
        Runner runner = runner(mc, health, "ignore-stop");
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        await(() -> status(health, "/ready") == 200);

        long start = System.currentTimeMillis();
        runner.stopGracefully();

        assertThat(exit.get(15, TimeUnit.SECONDS)).isNotZero();
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(2000);
    }

    @Test
    void readinessFailsWhenNothingListens() throws Exception {
        HealthServer h = new HealthServer(freePort(), () -> true, freePort());
        assertThat(h.ready(freePort())).isFalse();
    }
}
