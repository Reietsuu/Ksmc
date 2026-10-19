package io.ksmc.controller.bridge;

import io.ksmc.messaging.nats.NatsMessaging;
import io.ksmc.wire.Wire;
import io.ksmc.wire.bridge.BridgeSubjects;
import io.ksmc.wire.bridge.PlayerAction;
import io.ksmc.wire.bridge.PlayerInfo;
import io.ksmc.wire.messaging.KeyValueApi;
import io.nats.client.Message;
import io.nats.client.api.KeyValueEntry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Joueurs connectés (bucket KV {@code players}) et actions sur eux, exécutées par le proxy qui les héberge. */
public final class PlayerService {
    private final Supplier<NatsMessaging> messaging;

    public PlayerService(Supplier<NatsMessaging> messaging) {
        this.messaging = messaging;
    }

    private NatsMessaging m() {
        NatsMessaging m = messaging.get();
        if (m == null) {
            throw new IllegalStateException("messaging unavailable");
        }
        return m;
    }

    /** Joueurs en ligne, triés par nom. */
    public List<PlayerInfo> list() {
        List<PlayerInfo> out = new ArrayList<>();
        try {
            var kv = m().connection().keyValue(io.ksmc.messaging.nats.Subjects.bucket(
                    m().identity().namespace, BridgeSubjects.PLAYERS_BUCKET));
            for (String key : kv.keys()) {
                KeyValueEntry e = kv.get(key);
                if (e != null && e.getValue() != null) {
                    out.add(Wire.fromJson(new String(e.getValue(), StandardCharsets.UTF_8), PlayerInfo.class));
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("no keys")) {
                return out;
            }
            throw new IllegalStateException("players registry unavailable: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(p -> p.name.toLowerCase()));
        return out;
    }

    /** Joueur par UUID ou par nom (insensible à la casse). */
    public Optional<PlayerInfo> find(String uuidOrName) {
        KeyValueApi.Entry e = m().keyValue(BridgeSubjects.PLAYERS_BUCKET).get(uuidOrName);
        if (e != null) {
            return Optional.of(Wire.fromJson(e.valueAsString(), PlayerInfo.class));
        }
        return list().stream().filter(p -> p.name.equalsIgnoreCase(uuidOrName)).findFirst();
    }

    /** Exécute une action ; renvoie la réponse du proxy ({@code ok} ou {@code error: ...}). */
    public String act(PlayerAction action) {
        PlayerInfo p = find(action.player).orElse(null);
        if (p == null) {
            return "error: player not online: " + action.player;
        }
        action.player = p.uuid;
        try {
            Message reply = m().connection().request(BridgeSubjects.playerRpc(m().identity().namespace, p.proxy),
                    Wire.toJson(action).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            if (reply == null || reply.isStatusMessage()) {
                return "error: proxy " + p.proxy + " did not answer";
            }
            return new String(reply.getData(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error: interrupted";
        }
    }
}
