package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SoftwareDefinitionStatus extends KsmcStatus {
    public String latest;
    /** Versions connues (les plus récentes d'abord, tronqué). */
    public List<String> versions = new ArrayList<>();
    public String lastRefresh;
}
