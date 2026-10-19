package io.ksmc.plugin;

/** Message de channel. {@code data} est un JSON facultatif (texte), à lire avec la bibliothèque JSON du plugin. */
public final class Message {

    private final String channel;
    private final String message;
    private final String data;
    private final String senderService;

    public Message(String channel, String message, String data, String senderService) {
        this.channel = channel;
        this.message = message;
        this.data = data;
        this.senderService = senderService;
    }

    public String channel() {
        return channel;
    }

    public String message() {
        return message;
    }

    public String data() {
        return data;
    }

    /** Service émetteur, ou {@code null} (controller, module). */
    public String senderService() {
        return senderService;
    }

    /** Réponse à renvoyer depuis un {@link Channels.Handler} pour une requête. */
    public Message reply(String message) {
        return new Message(channel, message, null, null);
    }

    public Message reply(String message, String data) {
        return new Message(channel, message, data, null);
    }
}
