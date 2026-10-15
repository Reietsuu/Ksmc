package io.ksmc.controller.health;

/** Vérification de disponibilité d'une dépendance (API Kubernetes, NATS, module...). */
public interface HealthCheck {
    String name();

    Result check();

    record Result(boolean healthy, String detail) {
        public static Result up() {
            return new Result(true, "ok");
        }

        public static Result down(String detail) {
            return new Result(false, detail);
        }
    }
}
