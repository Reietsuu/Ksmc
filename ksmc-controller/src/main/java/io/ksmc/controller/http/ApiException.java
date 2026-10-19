package io.ksmc.controller.http;

import java.util.Map;

/** Erreur d'API rendue en JSON uniforme {@code {code, message, details}}. */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "InvalidRequest", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NotFound", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "Conflict", message);
    }

    public static ApiException forbidden(String permission) {
        return new ApiException(403, "Forbidden", "permission manquante : " + permission, Map.of("permission", permission));
    }
}
