package io.ksmc.wire.messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ksmc.wire.Wire;
import java.util.UUID;

/**
 * Message de channel : {@code channel} regroupe les messages d'une fonctionnalité (ex. {@code chat}),
 * {@code message} indique le type (ex. {@code broadcast}), {@code data} porte un JSON libre.
 */
public final class ChannelMessage {
    public static final int VERSION = 1;

    public int v = VERSION;
    public String id = UUID.randomUUID().toString();
    public String channel;
    public String message;
    public JsonElement data;
    public Sender sender;
    public Target target = Target.all();
    public long timestamp = System.currentTimeMillis();

    public ChannelMessage() {
    }

    public static ChannelMessage of(String channel, String message) {
        ChannelMessage m = new ChannelMessage();
        m.channel = channel;
        m.message = message;
        m.data = new JsonObject();
        return m;
    }

    public ChannelMessage target(Target target) {
        this.target = target;
        return this;
    }

    /** Données à partir d'un objet quelconque (sérialisé en JSON). */
    public ChannelMessage data(Object value) {
        this.data = value instanceof JsonElement ? (JsonElement) value : Wire.GSON.toJsonTree(value);
        return this;
    }

    /** Lit les données comme un objet Java. */
    public <T> T data(Class<T> type) {
        return Wire.GSON.fromJson(data, type);
    }

    /** Construit une réponse à ce message (même channel, destinataire = émetteur). */
    public ChannelMessage reply(String message) {
        ChannelMessage r = of(channel, message);
        r.target = sender == null || sender.type != Sender.Type.SERVICE ? Target.controller() : Target.service(sender.name);
        return r;
    }

    @Override
    public String toString() {
        return channel + "/" + message + " from " + sender + " to " + target;
    }
}
