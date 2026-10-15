package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceTemplateStatus extends KsmcStatus {
    /** Empreinte du contenu actuellement résolu (commit git, ETag, resourceVersion...). */
    public String digest;
    public String lastResolved;
}
