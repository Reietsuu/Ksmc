package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.PlayerInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SessionLockTest {
    final MemoryKeyValue kv = new MemoryKeyValue();
    Set<String> running = Set.of("proxy-1", "proxy-2");
    final SessionLock lock = new SessionLock(kv, name -> running.contains(name), () -> kv.entries.values().stream()
            .map(e -> Wire.fromJson(new String(e.value, StandardCharsets.UTF_8), PlayerInfo.class)).toList());

    static PlayerInfo steve(String proxy) {
        PlayerInfo p = new PlayerInfo();
        p.uuid = "uuid-steve";
        p.name = "Steve";
        p.proxy = proxy;
        return p;
    }

    String holder() {
        return Wire.fromJson(new String(kv.get("uuid-steve").value, StandardCharsets.UTF_8), PlayerInfo.class).proxy;
    }

    @Test
    void firstLoginTakesTheSession() {
        assertThat(lock.acquire(steve("proxy-1"), null).outcome).isEqualTo(SessionLock.Outcome.ACCEPTED);
        assertThat(holder()).isEqualTo("proxy-1");
    }

    @Test
    void secondProxyIsDeniedByDefault() {
        lock.acquire(steve("proxy-1"), null);
        SessionLock.Result r = lock.acquire(steve("proxy-2"), null);
        assertThat(r.outcome).isEqualTo(SessionLock.Outcome.DENIED);
        assertThat(r.holder).isEqualTo("proxy-1");
        assertThat(holder()).isEqualTo("proxy-1");
    }

    @Test
    void replacePolicyAsksToKickTheHolder() {
        lock.acquire(steve("proxy-1"), null);
        SessionLock.Result r = lock.acquire(steve("proxy-2"), SessionLock.REPLACE);
        assertThat(r.outcome).isEqualTo(SessionLock.Outcome.REPLACE);
        assertThat(r.holder).isEqualTo("proxy-1");
        lock.takeOver(steve("proxy-2"));
        assertThat(holder()).isEqualTo("proxy-2");
    }

    @Test
    void sessionOfAStoppedProxyIsOrphaned() {
        lock.acquire(steve("proxy-1"), null);
        running = Set.of("proxy-2");
        assertThat(lock.acquire(steve("proxy-2"), null).outcome).isEqualTo(SessionLock.Outcome.ACCEPTED);
        assertThat(holder()).isEqualTo("proxy-2");
    }

    @Test
    void sameNameWithAnotherCaseIsAnotherSessionOfTheSamePlayer() {
        lock.acquire(steve("proxy-1"), null);
        PlayerInfo lower = steve("proxy-2");
        lower.uuid = "uuid-lower";
        lower.name = "steve";
        SessionLock.Result r = lock.acquire(lower, null);
        assertThat(r.outcome).isEqualTo(SessionLock.Outcome.DENIED);
        r = lock.acquire(lower, SessionLock.REPLACE);
        assertThat(r.outcome).isEqualTo(SessionLock.Outcome.REPLACE);
        assertThat(r.heldUuid).isEqualTo("uuid-steve");
        running = Set.of("proxy-2");
        assertThat(lock.acquire(lower, null).outcome).isEqualTo(SessionLock.Outcome.ACCEPTED);
    }

    @Test
    void sameProxyReconnectIsAccepted() {
        lock.acquire(steve("proxy-1"), null);
        assertThat(lock.acquire(steve("proxy-1"), null).outcome).isEqualTo(SessionLock.Outcome.ACCEPTED);
    }

    @Test
    void concurrentLoginsAcceptExactlyOne() throws Exception {
        for (int round = 0; round < 50; round++) {
            kv.entries.clear();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Callable<SessionLock.Outcome> on1 = () -> {
                go.await();
                return lock.acquire(steve("proxy-1"), null).outcome;
            };
            Callable<SessionLock.Outcome> on2 = () -> {
                go.await();
                return lock.acquire(steve("proxy-2"), null).outcome;
            };
            List<Future<SessionLock.Outcome>> results = List.of(pool.submit(on1), pool.submit(on2));
            go.countDown();
            long accepted = 0;
            for (Future<SessionLock.Outcome> f : results) {
                accepted += f.get() == SessionLock.Outcome.ACCEPTED ? 1 : 0;
            }
            pool.shutdown();
            assertThat(accepted).isEqualTo(1);
        }
    }
}
