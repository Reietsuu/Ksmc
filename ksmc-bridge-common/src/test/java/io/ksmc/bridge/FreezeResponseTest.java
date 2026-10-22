package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.manifest.ServiceManifest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class FreezeResponseTest {
    final List<String> actions = new CopyOnWriteArrayList<>();

    KsmcBridge bridge(String... props) {
        ServiceManifest m = new ServiceManifest();
        for (int i = 0; i < props.length; i += 2) {
            m.properties.put(props[i], props[i + 1]);
        }
        m.properties.put("ksmc.watchdog.leave-delay-ms", "0");
        m.properties.put("ksmc.transfer.delay-ms", "0");
        // pas de NATS : drain() décide seul (aucun autre proxy connu → expulsion)
        return new KsmcBridge(new BridgeEnv("prod", "proxy-1", "proxy", "proxy", null, null, null, m), new FakePlatform());
    }

    FreezeResponse.Actions fake(CountDownLatch block) {
        return new FreezeResponse.Actions() {
            public List<TransferPlan.Candidate> candidates() {
                return List.of(new TransferPlan.Candidate("u1", 770, "play.example.com", 25565));
            }

            public void execute(TransferPlan.Step step) {
                actions.add((step.isTransfer() ? "transfer " : "kick ") + step.uuid);
                if (block != null) {
                    try {
                        block.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            public void sleep(long millis) {
                actions.add("sleep " + millis);
            }

            public void halt(int code) {
                actions.add("halt " + code);
            }
        };
    }

    @Test
    void frozenProxyLeavesDrainsAndHalts() {
        new FreezeResponse(bridge(), fake(null)).run("bus d'événements sans réponse depuis 31 s");
        assertThat(actions).containsExactly("sleep 0", "kick u1", "halt 70");
    }

    @Test
    void blockedNetworkDoesNotPreventTheRestart() {
        long start = System.currentTimeMillis();
        new FreezeResponse(bridge("ksmc.watchdog.drain-timeout-seconds", "1"), fake(new CountDownLatch(1)))
                .run("planificateur sans réponse");
        assertThat(actions).containsExactly("sleep 0", "kick u1", "halt 70");
        assertThat(System.currentTimeMillis() - start).isBetween(900L, 5000L);
    }

    @Test
    void threadDumpListsThreads() {
        assertThat(FreezeResponse.threadDump()).contains("\"" + Thread.currentThread().getName() + "\"").contains("    at ");
    }
}
