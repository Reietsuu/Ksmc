package io.ksmc.wire.messaging;

import com.google.gson.JsonElement;
import java.util.UUID;

/**
 * Événement du cloud (type pointé, ex. {@code service.ready}, {@code player.login}, {@code module.stats.record}),
 * avec un instantané de la ressource concernée dans {@code data}.
 */
public final class CloudEvent {
    public static final int VERSION = 1;

    public int v = VERSION;
    public String id = UUID.randomUUID().toString();
    public String type;
    public String namespace;
    /** Nom de la ressource concernée (service, task, joueur...). */
    public String subject;
    public String task;
    /** {@code proxy} ou {@code server}, si applicable. */
    public String environment;
    public JsonElement data;
    public long timestamp = System.currentTimeMillis();
    /** Numéro de séquence attribué par le transport (pour reprendre un abonnement), 0 si inconnu. */
    public transient long sequence;

    public CloudEvent() {
    }

    public static CloudEvent of(String type, String subject) {
        CloudEvent e = new CloudEvent();
        e.type = type;
        e.subject = subject;
        return e;
    }

    @Override
    public String toString() {
        return type + " " + subject + (sequence > 0 ? " #" + sequence : "");
    }
}
