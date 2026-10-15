package io.ksmc.wire.messaging;

/** Qui se connecte à la messagerie : détermine les messages reçus (ciblage) et le nom d'émetteur. */
public final class Identity {
    public final String namespace;
    public final Sender sender;
    /** Pour un service : sa task et son environnement (sinon {@code null}). */
    public final String task;
    public final String environment;

    public Identity(String namespace, Sender sender, String task, String environment) {
        this.namespace = namespace;
        this.sender = sender;
        this.task = task;
        this.environment = environment;
    }

    public static Identity controller(String namespace) {
        return new Identity(namespace, new Sender(Sender.Type.CONTROLLER, "controller"), null, null);
    }

    public static Identity service(String namespace, String service, String task, String environment) {
        return new Identity(namespace, new Sender(Sender.Type.SERVICE, service), task, environment);
    }

    public boolean isController() {
        return sender.type != Sender.Type.SERVICE;
    }
}
