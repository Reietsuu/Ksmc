package io.ksmc.bridge;

import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.messaging.KeyValueApi;
import io.ksmc.wire.messaging.MessagingException;

/**
 * Session unique par joueur sur le réseau : l'entrée du joueur dans le bucket {@code players} sert de verrou,
 * prise par comparer-et-écrire. Une entrée tenue par un proxy qui n'est plus {@code Running} est orpheline.
 */
final class SessionLock {
    static final String DENY = "deny";
    static final String REPLACE = "replace";
    private static final int ATTEMPTS = 3;

    /** Vrai si le service proxy {@code name} tourne encore. */
    interface Liveness {
        boolean isRunning(String name);
    }

    enum Outcome {
        /** Verrou pris : la connexion est acceptée. */
        ACCEPTED,
        /** Session active ailleurs, politique {@code deny}. */
        DENIED,
        /** Session active ailleurs, politique {@code replace} : expulser {@link Result#holder} puis {@link #takeOver}. */
        REPLACE
    }

    static final class Result {
        final Outcome outcome;
        /** Proxy qui tient la session (DENIED, REPLACE). */
        final String holder;

        Result(Outcome outcome, String holder) {
            this.outcome = outcome;
            this.holder = holder;
        }
    }

    private final KeyValueApi kv;
    private final Liveness liveness;

    SessionLock(KeyValueApi kv, Liveness liveness) {
        this.kv = kv;
        this.liveness = liveness;
    }

    /** Tente de prendre la session de {@code p} pour {@code p.proxy}. */
    Result acquire(PlayerInfo p, String policy) {
        byte[] value = Wire.toJson(p).getBytes(KsmcBridge.UTF8);
        String holder = null;
        for (int i = 0; i < ATTEMPTS; i++) {
            KeyValueApi.Entry e = kv.get(p.uuid);
            holder = e == null ? null : holderOf(e);
            if (e != null && holder != null && !holder.equals(p.proxy) && liveness.isRunning(holder)) {
                return new Result(REPLACE.equals(policy) ? Outcome.REPLACE : Outcome.DENIED, holder);
            }
            try {
                kv.update(p.uuid, value, e == null ? 0 : e.revision);
                return new Result(Outcome.ACCEPTED, null);
            } catch (MessagingException conflict) {
                if (!"Conflict".equals(conflict.code())) {
                    throw conflict;
                }
                // un autre proxy vient d'écrire : relire
            }
        }
        return new Result(Outcome.DENIED, holder);
    }

    /** Prend la session sans condition (après expulsion de l'ancienne, politique {@code replace}). */
    void takeOver(PlayerInfo p) {
        kv.put(p.uuid, Wire.toJson(p).getBytes(KsmcBridge.UTF8));
    }

    /** Proxy qui tient actuellement la session de {@code uuid}, ou {@code null}. */
    String holder(String uuid) {
        KeyValueApi.Entry e = kv.get(uuid);
        return e == null ? null : holderOf(e);
    }

    private static String holderOf(KeyValueApi.Entry e) {
        if (e.value == null) {
            return null;
        }
        try {
            PlayerInfo held = Wire.fromJson(e.valueAsString(), PlayerInfo.class);
            return held == null ? null : held.proxy;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
