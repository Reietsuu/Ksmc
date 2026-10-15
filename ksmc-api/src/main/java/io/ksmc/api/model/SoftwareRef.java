package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;

/** Référence à une {@link SoftwareDefinition} et à une version. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SoftwareRef {
    /** Nom de la SoftwareDefinition (ex. {@code paper}). */
    @Required
    public String name;
    /** Version exacte, {@code latest}, ou {@code <version>@<build>}. */
    @Default("latest")
    public String version = "latest";
    /** Image runtime imposée ; sinon choisie selon la version Minecraft. */
    public String image;

    public SoftwareRef() {
    }

    public SoftwareRef(String name, String version) {
        this.name = name;
        this.version = version;
    }
}
