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

    /** Joueurs du registre (recherche par pseudo). */
    interface Directory {
        java.util.List<PlayerInfo> players();
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
        /** UUID de la session à expulser (REPLACE) : celui du joueur, ou d'un homonyme à la casse près. */
        final String heldUuid;

        Result(Outcome outcome, String holder, String heldUuid) {
            this.outcome = outcome;
            this.holder = holder;
            this.heldUuid = heldUuid;
        }
    }

    private final KeyValueApi kv;
    private final Liveness liveness;
    private final Directory directory;

    SessionLock(KeyValueApi kv, Liveness liveness, Directory directory) {
        this.kv = kv;
        this.liveness = liveness;
        this.directory = directory;
    }

    /** Tente de prendre la session de {@code p} pour {@code p.proxy}. */
    Result acquire(PlayerInfo p, String policy) {
        Outcome conflict = REPLACE.equals(policy) ? Outcome.REPLACE : Outcome.DENIED;
        // Pseudo déjà en ligne sous un autre UUID (mode hors ligne : l'UUID dépend de la casse).
        for (PlayerInfo other : directory.players()) {
            if (other.name != null && other.name.equalsIgnoreCase(p.name) && !p.uuid.equals(other.uuid)
                    && other.proxy != null && liveness.isRunning(other.proxy)) {
                return new Result(conflict, other.proxy, other.uuid);
            }
        }
        byte[] value = Wire.toJson(p).getBytes(KsmcBridge.UTF8);
        String holder = null;
        for (int i = 0; i < ATTEMPTS; i++) {
            KeyValueApi.Entry e = kv.get(p.uuid);
            holder = e == null ? null : holderOf(e);
            if (e != null && holder != null && !holder.equals(p.proxy) && liveness.isRunning(holder)) {
                return new Result(conflict, holder, p.uuid);
            }
            try {
                kv.update(p.uuid, value, e == null ? 0 : e.revision);
                return new Result(Outcome.ACCEPTED, null, null);
            } catch (MessagingException race) {
                if (!"Conflict".equals(race.code())) {
                    throw race;
                }
                // un autre proxy vient d'écrire : relire
            }
        }
        return new Result(Outcome.DENIED, holder, p.uuid);
    }

    /** Prend la session sans condition (après expulsion de l'ancienne, politique {@code replace}). */
    void takeOver(PlayerInfo p) {
        kv.put(p.uuid, Wire.toJson(p).getBytes(KsmcBridge.UTF8));
    }

    boolean isLive(String proxy) {
        return liveness.isRunning(proxy);
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
