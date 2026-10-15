package io.ksmc.wrapper;

/** Journalisation minimale sur la sortie standard (visible dans {@code kubectl logs}). */
final class Log {
    private Log() {
    }

    static void info(String msg) {
        System.out.println("[ksmc] " + msg);
        System.out.flush();
    }

    static void warn(String msg) {
        System.out.println("[ksmc] WARN " + msg);
        System.out.flush();
    }
}
