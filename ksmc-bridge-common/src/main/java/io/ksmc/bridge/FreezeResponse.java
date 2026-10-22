package io.ksmc.bridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Réaction à un gel détecté par le {@link Watchdog} (thread du chien de garde, jamais un thread du proxy) :
 * signal au wrapper (sortie du Service), attente que le Pod ne reçoive plus de joueurs, drainage borné dans le
 * temps, vidage des threads dans les logs, puis arrêt immédiat de la JVM.
 */
public final class FreezeResponse {
    /** Code de sortie d'un proxy arrêté par son chien de garde. */
    public static final int EXIT_CODE = 70;

    /** Actions propres à la plateforme. */
    public interface Actions {
        /** Joueurs connectés, avec l'adresse qu'ils ont utilisée. */
        List<TransferPlan.Candidate> candidates();

        /** Transfère ou expulse un joueur (peut ne jamais revenir si le réseau du proxy est bloqué). */
        void execute(TransferPlan.Step step);

        void sleep(long millis) throws InterruptedException;

        /** Arrête la JVM sans hooks (un hook bloqué empêcherait l'arrêt). */
        void halt(int code);
    }

    private final KsmcBridge bridge;
    private final Actions actions;

    public FreezeResponse(KsmcBridge bridge, Actions actions) {
        this.bridge = bridge;
        this.actions = actions;
    }

    public void run(String reason) {
        Map<String, String> props = bridge.env().properties();
        bridge.log("[ksmc] proxy figé (" + reason + ") : sortie du Service, transfert des joueurs, redémarrage");
        bridge.markFrozen(reason);
        bridge.reportFrozen(reason);
        try {
            // La readiness échoue en ≤ 10 s : le Service n'envoie plus de joueurs ici.
            actions.sleep(longProperty(props, "ksmc.watchdog.leave-delay-ms", 10000));
            drain(longProperty(props, "ksmc.watchdog.drain-timeout-seconds", 10) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            bridge.log("[ksmc] drainage du proxy figé interrompu : " + e);
        }
        bridge.log(threadDump());
        actions.halt(EXIT_CODE);
    }

    /** Drainage dans un thread à part, abandonné au bout de {@code timeoutMillis}. */
    private void drain(long timeoutMillis) throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ksmc-freeze-drain");
                t.setDaemon(true);
                return t;
            }
        });
        pool.execute(new Runnable() {
            public void run() {
                int transferred = 0;
                List<TransferPlan.Step> steps = bridge.drain(actions.candidates());
                for (TransferPlan.Step s : steps) {
                    actions.execute(s);
                    transferred += s.isTransfer() ? 1 : 0;
                }
                bridge.log("[ksmc] proxy figé : " + transferred + " joueur(s) transféré(s), "
                        + (steps.size() - transferred) + " déconnecté(s)");
            }
        });
        pool.shutdown();
        if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
            bridge.log("[ksmc] drainage du proxy figé non terminé après " + timeoutMillis / 1000 + " s : arrêt quand même");
        }
    }

    static String threadDump() {
        StringBuilder b = new StringBuilder("[ksmc] threads du proxy figé :\n");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            b.append('"').append(t.getName()).append("\" ").append(t.getState()).append('\n');
            for (StackTraceElement el : e.getValue()) {
                b.append("    at ").append(el).append('\n');
            }
        }
        return b.toString();
    }

    static long longProperty(Map<String, String> props, String key, long def) {
        try {
            String v = props.get(key);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
