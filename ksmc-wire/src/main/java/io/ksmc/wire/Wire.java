package io.ksmc.wire;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Sérialisation JSON du protocole Ksmc (compatible Java 8, champs inconnus ignorés). */
public final class Wire {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Wire() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
