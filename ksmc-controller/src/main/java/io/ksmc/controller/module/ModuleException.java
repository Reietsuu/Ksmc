package io.ksmc.controller.module;

/** Échec de chargement ou d'activation d'un module ; {@link #reason} va dans {@code status.reason}. */
public final class ModuleException extends Exception {

    public static final String INVALID_DESCRIPTOR = "InvalidDescriptor";
    public static final String INCOMPATIBLE_API = "IncompatibleApiVersion";
    public static final String MISSING_DEPENDENCY = "MissingDependency";
    public static final String DEPENDENCY_CYCLE = "DependencyCycle";
    public static final String DUPLICATE_ID = "DuplicateModuleId";
    public static final String LOAD_ERROR = "LoadError";
    public static final String ENABLE_ERROR = "EnableError";
    public static final String CONFIG_ERROR = "ConfigError";
    public static final String CHECKSUM_MISMATCH = "ChecksumMismatch";
    public static final String DOWNLOAD_ERROR = "DownloadError";

    private final String reason;

    public ModuleException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModuleException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
