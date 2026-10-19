package io.ksmc.plugin;

/** Filtre de services ; les critères non définis sont ignorés. */
public final class ServiceQuery {

    String task;
    String environment;
    String state;
    boolean runningOnly;

    public static ServiceQuery all() {
        return new ServiceQuery();
    }

    public static ServiceQuery task(String task) {
        return new ServiceQuery().withTask(task);
    }

    public ServiceQuery withTask(String task) {
        this.task = task;
        return this;
    }

    /** {@code server} ou {@code proxy}. */
    public ServiceQuery environment(String environment) {
        this.environment = environment;
        return this;
    }

    /** État personnalisé (ex. {@code LOBBY}). */
    public ServiceQuery state(String state) {
        this.state = state;
        return this;
    }

    /** Seulement les services en phase {@code Running}. */
    public ServiceQuery running() {
        this.runningOnly = true;
        return this;
    }

    public boolean matches(CloudServiceInfo s) {
        return (task == null || task.equals(s.task()))
                && (environment == null || environment.equalsIgnoreCase(s.environment()))
                && (state == null || state.equals(s.state()))
                && (!runningOnly || s.isRunning());
    }
}
