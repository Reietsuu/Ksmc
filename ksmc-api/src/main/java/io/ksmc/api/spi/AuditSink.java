package io.ksmc.api.spi;

/** Reçoit chaque entrée d'audit de l'API (export vers un SIEM, une base...). Appelé hors du fil de la requête. */
public interface AuditSink {

    /**
     * @param time    instant ISO-8601
     * @param user    utilisateur (ou nom tenté pour une connexion refusée)
     * @param action  ex. {@code service.stop}, {@code task.create}, {@code auth.login}
     * @param target  ressource visée
     * @param result  {@code ok}, {@code denied}, {@code error}
     * @param detail  précision libre, ou {@code null}
     */
    record Entry(String time, String user, String action, String target, String result, String detail) {
    }

    void record(Entry entry);
}
