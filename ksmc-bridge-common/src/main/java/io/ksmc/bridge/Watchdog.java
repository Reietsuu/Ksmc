package io.ksmc.bridge;

/**
 * Chien de garde d'un proxy : la plateforme signale des battements depuis son planificateur et son bus
 * d'événements ; un thread indépendant déclare le proxy figé si l'un d'eux se tait plus de {@code timeoutMillis}.
 * Un battement n'est surveillé qu'après sa première occurrence (démarrage du proxy ignoré). Le gel n'est signalé
 * qu'une fois.
 */
public final class Watchdog {
    public static final String SCHEDULER = "planificateur";
    public static final String EVENTS = "bus d'événements";
    static final long CHECK_PERIOD_MILLIS = 1000;

    public interface Clock {
        long millis();
    }

    public interface Listener {
        /** Appelé une seule fois, depuis le thread du chien de garde. */
        void frozen(String reason);
    }

    private final long timeoutMillis;
    private final Clock clock;
    private volatile long schedulerBeat;
    private volatile long eventsBeat;
    private volatile String frozen;
    private volatile Thread thread;

    public Watchdog(long timeoutMillis, Clock clock) {
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
    }

    public void beatScheduler() {
        schedulerBeat = clock.millis();
    }

    public void beatEvents() {
        eventsBeat = clock.millis();
    }

    /** Raison du gel, ou {@code null} si le proxy répond. */
    public String frozenReason() {
        return frozen;
    }

    /** Vérifie les battements ; renvoie la raison si le proxy vient d'être déclaré figé, sinon {@code null}. */
    String check() {
        if (frozen != null) {
            return null;
        }
        long now = clock.millis();
        String reason = silent(SCHEDULER, schedulerBeat, now);
        if (reason == null) {
            reason = silent(EVENTS, eventsBeat, now);
        }
        if (reason != null) {
            frozen = reason;
        }
        return reason;
    }

    private String silent(String what, long beat, long now) {
        if (beat == 0 || now - beat <= timeoutMillis) {
            return null;
        }
        return what + " sans réponse depuis " + (now - beat) / 1000 + " s";
    }

    /** Démarre le thread de surveillance (démon). */
    public synchronized void start(final Listener listener) {
        if (thread != null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(CHECK_PERIOD_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    String reason = check();
                    if (reason != null) {
                        listener.frozen(reason);
                        return;
                    }
                }
            }
        }, "ksmc-watchdog");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Arrête la surveillance (arrêt normal du proxy). */
    public synchronized void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
