package io.ksmc.messaging.nats;

import io.ksmc.wire.messaging.Identity;
import io.ksmc.wire.messaging.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Construction des sujets NATS de Ksmc ({@code ksmc.<ns>.<domaine>...}). */
public final class Subjects {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    private Subjects() {
    }

    static String token(String value, String what) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + what + " (letters, digits, '_' and '-' only): " + value);
        }
        return value;
    }

    /** Sujet de publication d'un message de channel. */
    public static String channel(String ns, String channel, Target target) {
        String base = "ksmc." + token(ns, "namespace") + ".ch." + token(channel, "channel") + ".";
        switch (target.type) {
            case ALL:
                return base + "all";
            case CONTROLLER:
                return base + "controller";
            case SERVICE:
                return base + "service." + token(target.name, "service");
            case TASK:
                return base + "task." + token(target.name, "task");
            case ENVIRONMENT:
                return base + "env." + token(target.name, "environment");
            default:
                throw new IllegalArgumentException("unknown target " + target);
        }
    }

    /** Sujets écoutés par un composant pour un channel (messages qui le concernent). */
    public static List<String> channelInbox(Identity id, String channel) {
        String base = "ksmc." + token(id.namespace, "namespace") + ".ch." + token(channel, "channel") + ".";
        List<String> out = new ArrayList<String>();
        out.add(base + "all");
        if (id.isController()) {
            out.add(base + "controller");
        } else {
            out.add(base + "service." + token(id.sender.name, "service"));
            if (id.task != null && !id.task.isEmpty()) {
                out.add(base + "task." + token(id.task, "task"));
            }
            if (id.environment != null) {
                out.add(base + "env." + token(id.environment, "environment"));
            }
        }
        return out;
    }

    public static String channelAll(String ns, String channel) {
        return "ksmc." + token(ns, "namespace") + ".ch." + token(channel, "channel") + ".>";
    }

    /** Sujet d'un événement : {@code service.ready} → {@code ksmc.<ns>.ev.service.ready}. */
    public static String event(String ns, String type) {
        for (String part : type.split("\\.")) {
            if (!part.equals("*") && !part.equals(">")) {
                token(part, "event type");
            }
        }
        return "ksmc." + token(ns, "namespace") + ".ev." + type;
    }

    public static String eventStream(String ns) {
        return "KSMC_EVENTS_" + ns.replace('-', '_').toUpperCase();
    }

    /** Commandes console à destination du wrapper d'un service. */
    public static String command(String ns, String service) {
        return "ksmc." + token(ns, "namespace") + ".cmd." + token(service, "service");
    }

    public static String bucket(String ns, String bucket) {
        return "ksmc_" + token(ns, "namespace").replace('-', '_') + "_" + token(bucket, "bucket");
    }
}
