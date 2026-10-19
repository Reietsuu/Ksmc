package io.ksmc.controller.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite les échecs de connexion : au-delà de {@code max} échecs en {@code window} pour une même clé (compte, IP),
 * les tentatives sont refusées jusqu'à ce que la fenêtre se libère (au moins {@code window} après le dernier échec).
 */
public final class LoginRateLimiter {

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** Secondes avant la prochaine tentative autorisée, ou 0. */
    public long retryAfter(String key) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            prune(q);
            if (q.size() < max) {
                return 0;
            }
            Instant until = q.peekLast().plus(window);
            return Math.max(1, Duration.between(clock.instant(), until).toSeconds());
        }
    }

    public void failure(String key) {
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
    }

    public void success(String key) {
        failures.remove(key);
    }

    /** Oublie les échecs anciens, sauf si le blocage est en cours (dernier échec trop récent). */
    private void prune(Deque<Instant> q) {
        Instant limit = clock.instant().minus(window);
        if (q.size() >= max && q.peekLast().isAfter(limit)) {
            return;
        }
        while (!q.isEmpty() && !q.peekFirst().isAfter(limit)) {
            q.pollFirst();
        }
    }
}
