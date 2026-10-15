package io.ksmc.wire.messaging;

import java.util.concurrent.CompletableFuture;

/** Messages de channel entre composants d'un même namespace. */
public interface ChannelApi {

    /** Traite un message reçu ; peut renvoyer une réponse (utilisée si l'émetteur a fait une requête), ou {@code null}. */
    interface Handler {
        ChannelMessage handle(ChannelMessage message);
    }

    /** Envoie un message (au plus une fois, sans attendre). L'émetteur est renseigné automatiquement. */
    void send(ChannelMessage message);

    /** Requête : renvoie la première réponse, ou échoue avec {@code Timeout} / {@code NoResponders}. */
    CompletableFuture<ChannelMessage> request(ChannelMessage message, long timeoutMillis);

    /** Reçoit les messages du channel destinés à ce composant (tous, lui, sa task, son environnement). */
    Subscription subscribe(String channel, Handler handler);

    /** Reçoit tous les messages du channel quel que soit le destinataire (supervision, modules). Controller uniquement. */
    Subscription subscribeAll(String channel, Handler handler);
}
