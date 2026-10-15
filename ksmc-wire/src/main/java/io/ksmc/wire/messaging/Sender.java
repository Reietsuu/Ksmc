package io.ksmc.wire.messaging;

/** Émetteur d'un message : le controller, un module du controller, ou un service. */
public final class Sender {
    public enum Type { CONTROLLER, MODULE, SERVICE }

    public Type type;
    public String name;

    public Sender() {
    }

    public Sender(Type type, String name) {
        this.type = type;
        this.name = name;
    }

    @Override
    public String toString() {
        return type.name().toLowerCase() + "/" + name;
    }
}
