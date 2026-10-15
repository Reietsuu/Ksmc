package io.ksmc.wire.messaging;

/** Destinataires d'un message de channel. */
public final class Target {
    public enum Type { ALL, SERVICE, TASK, ENVIRONMENT, CONTROLLER }

    public Type type;
    /** Nom du service / de la task, ou {@code proxy}/{@code server} pour un environnement. */
    public String name;

    public Target() {
    }

    private Target(Type type, String name) {
        this.type = type;
        this.name = name;
    }

    public static Target all() {
        return new Target(Type.ALL, null);
    }

    public static Target service(String name) {
        return new Target(Type.SERVICE, name);
    }

    public static Target task(String name) {
        return new Target(Type.TASK, name);
    }

    public static Target environment(String environment) {
        return new Target(Type.ENVIRONMENT, environment);
    }

    public static Target controller() {
        return new Target(Type.CONTROLLER, null);
    }

    /** Analyse {@code all}, {@code controller}, {@code service:<n>}, {@code task:<n>}, {@code environment:<env>}. */
    public static Target parse(String value) {
        String v = value.trim();
        if (v.equals("all")) {
            return all();
        }
        if (v.equals("controller")) {
            return controller();
        }
        int colon = v.indexOf(':');
        if (colon > 0) {
            String kind = v.substring(0, colon);
            String name = v.substring(colon + 1);
            if (kind.equals("service")) {
                return service(name);
            }
            if (kind.equals("task")) {
                return task(name);
            }
            if (kind.equals("environment") || kind.equals("env")) {
                return environment(name);
            }
        }
        throw new IllegalArgumentException("invalid target: " + value);
    }

    @Override
    public String toString() {
        return name == null ? type.name().toLowerCase() : type.name().toLowerCase() + ":" + name;
    }
}
