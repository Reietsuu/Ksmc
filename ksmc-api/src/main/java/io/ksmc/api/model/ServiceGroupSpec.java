package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ServiceGroupSpec extends RuntimeConfig {
    /** Environnements auxquels le groupe s'applique automatiquement, sans être référencé. */
    public List<Environment> targetEnvironments = new ArrayList<>();
}
