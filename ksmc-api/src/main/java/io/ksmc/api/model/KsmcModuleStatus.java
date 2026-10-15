package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class KsmcModuleStatus extends KsmcStatus {
    public enum Phase { Pending, Loaded, Enabled, Disabled, Failed }

    public Phase phase;
    public String reason;
    public String message;
    public String moduleId;
    public String loadedVersion;
}
