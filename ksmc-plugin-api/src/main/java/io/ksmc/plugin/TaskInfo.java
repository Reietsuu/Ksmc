package io.ksmc.plugin;

/** Résumé d'une task d'après ses services connus. */
public final class TaskInfo {

    private final String name;
    private final String environment;
    private final int services;
    private final int runningServices;
    private final int onlinePlayers;

    public TaskInfo(String name, String environment, int services, int runningServices, int onlinePlayers) {
        this.name = name;
        this.environment = environment;
        this.services = services;
        this.runningServices = runningServices;
        this.onlinePlayers = onlinePlayers;
    }

    public String name() {
        return name;
    }

    public String environment() {
        return environment;
    }

    public int services() {
        return services;
    }

    public int runningServices() {
        return runningServices;
    }

    public int onlinePlayers() {
        return onlinePlayers;
    }
}
