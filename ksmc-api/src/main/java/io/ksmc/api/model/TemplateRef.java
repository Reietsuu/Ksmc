package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.generator.annotation.Required;

/** Référence ordonnée à un {@link ServiceTemplate}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateRef {
    @Required
    public String name;
    /** Si vrai, une source indisponible n'empêche pas le démarrage (événement Warning). */
    public Boolean optional;

    public TemplateRef() {
    }

    public TemplateRef(String name) {
        this.name = name;
    }
}
