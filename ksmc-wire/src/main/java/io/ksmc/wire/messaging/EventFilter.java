package io.ksmc.wire.messaging;

/** Filtre d'abonnement aux événements (champs nuls = pas de filtre). */
public final class EventFilter {
    /** Type ou motif avec jokers NATS : {@code service.*}, {@code player.>}, {@code >} (tout). */
    public String type = ">";
    public String task;
    public String environment;
    /** Rejouer depuis cette séquence (incluse), ou 0. */
    public long sinceSequence;
    /** Rejouer depuis cet instant (ms epoch), ou 0. Sans séquence ni instant : seulement les nouveaux événements. */
    public long sinceTime;

    public static EventFilter type(String type) {
        EventFilter f = new EventFilter();
        f.type = type;
        return f;
    }

    public EventFilter task(String task) {
        this.task = task;
        return this;
    }

    public EventFilter environment(String environment) {
        this.environment = environment;
        return this;
    }

    public EventFilter sinceSequence(long sequence) {
        this.sinceSequence = sequence;
        return this;
    }

    public EventFilter sinceTime(long epochMillis) {
        this.sinceTime = epochMillis;
        return this;
    }

    /** Filtrage local des champs non couverts par le sujet NATS. */
    public boolean matches(CloudEvent e) {
        return (task == null || task.equals(e.task)) && (environment == null || environment.equals(e.environment));
    }
}
