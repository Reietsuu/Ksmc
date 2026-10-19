package io.ksmc.plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Messages entre services, modules et controller. Cible ({@code target}) : {@code all}, {@code service:<nom>},
 * {@code task:<nom>}, {@code environment:server|proxy} ou {@code controller}.
 */
public interface Channels {

    /** Gestionnaire appelé sur un thread de messagerie (pas le thread principal) pour pouvoir répondre aussitôt. */
    interface Handler {
        /** Réponse à une requête ({@link Message#reply}), ou {@code null}. */
        Message handle(Message message);
    }

    void send(String target, String channel, String message);

    void send(String target, String channel, String message, String data);

    /** Requête/réponse : le futur échoue après {@code timeoutMillis} sans réponse. */
    CompletableFuture<Message> request(String target, String channel, String message, String data, long timeoutMillis);

    /** Reçoit les messages du channel destinés à ce service (cible all, ce service, sa task ou son environnement). */
    Subscription subscribe(String channel, Handler handler);
}
