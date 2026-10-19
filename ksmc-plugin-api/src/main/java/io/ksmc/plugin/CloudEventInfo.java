package io.ksmc.plugin;

/** Événement du cloud (ex. {@code service.started}, {@code player.login}). */
public final class CloudEventInfo {

    private final String type;
    private final String subject;
    private final String task;
    private final String environment;
    private final String data;
    private final long timestamp;

    public CloudEventInfo(String type, String subject, String task, String environment, String data, long timestamp) {
        this.type = type;
        this.subject = subject;
        this.task = task;
        this.environment = environment;
        this.data = data;
        this.timestamp = timestamp;
    }

    public String type() {
        return type;
    }

    /** Objet de l'événement (nom du service, du joueur...). */
    public String subject() {
        return subject;
    }

    public String task() {
        return task;
    }

    public String environment() {
        return environment;
    }

    /** Données JSON (texte), ou {@code null}. */
    public String data() {
        return data;
    }

    public long timestamp() {
        return timestamp;
    }
}
