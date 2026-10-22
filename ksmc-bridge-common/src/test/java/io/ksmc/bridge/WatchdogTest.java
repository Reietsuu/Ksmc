package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WatchdogTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final Watchdog watchdog = new Watchdog(30_000, now::get);

    void advance(long millis) {
        now.addAndGet(millis);
    }

    @Test
    void startupIsIgnoredUntilTheFirstBeat() {
        advance(120_000);
        assertThat(watchdog.check()).isNull();
    }

    @Test
    void silentEventBusFreezesTheProxyOnce() {
        watchdog.beatScheduler();
        watchdog.beatEvents();
        for (int i = 1; i <= 30; i++) {
            advance(1000);
            watchdog.beatScheduler();
            assertThat(watchdog.check()).as("après %d s", i).isNull();
        }
        advance(1000);
        assertThat(watchdog.check()).isEqualTo("bus d'événements sans réponse depuis 31 s");
        assertThat(watchdog.frozenReason()).isEqualTo("bus d'événements sans réponse depuis 31 s");
        advance(10_000);
        assertThat(watchdog.check()).isNull();
    }

    @Test
    void lateButAliveBeatsAreTolerated() {
        watchdog.beatScheduler();
        watchdog.beatEvents();
        for (int i = 0; i < 10; i++) {
            advance(5000);
            assertThat(watchdog.check()).isNull();
            watchdog.beatScheduler();
            watchdog.beatEvents();
        }
        assertThat(watchdog.frozenReason()).isNull();
    }

    @Test
    void silentSchedulerIsReported() {
        watchdog.beatScheduler();
        watchdog.beatEvents();
        advance(31_000);
        watchdog.beatEvents();
        assertThat(watchdog.check()).startsWith("planificateur sans réponse depuis 31 s");
        assertThat(watchdog.check()).isNull();
    }

    @Test
    void threadNotifiesTheListener() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Watchdog real = new Watchdog(50, System::currentTimeMillis);
        real.beatScheduler();
        real.beatEvents();
        real.start(r -> {
            reason.set(r);
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reason.get()).contains("sans réponse");
        real.stop();
    }
}
