package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.ResourceRequirements;

/**
 * Configuration effective d'une instance. Pour un service issu d'une task, le controller y recopie
 * la configuration fusionnée (groupes + task) au moment de sa création.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class CloudServiceSpec extends RuntimeConfig {
    public enum DesiredState { Running, Stopped }

    public enum RestartPolicy { Never, OnFailure, Always }

    /** Task d'origine ; absent pour un service autonome. */
    public String task;
    @Required
    public Environment environment;
    @Required
    public SoftwareRef software;
    @Min(256)
    @Default("1024")
    public int memoryMb = 1024;
    public ResourceRequirements resources;
    @JsonProperty("static")
    public boolean staticService;
    public ServiceTaskSpec.StorageSpec storage;
    @Default("Running")
    public DesiredState desiredState = DesiredState.Running;
    @Default("Never")
    public RestartPolicy restartPolicy = RestartPolicy.Never;
    @Min(1)
    @Max(3600)
    @Default("30")
    public int stopTimeoutSeconds = 30;
    public ServiceTaskSpec.DeployOnStop deployOnStop;
    public ServiceTaskSpec.Bridge bridge;
}
