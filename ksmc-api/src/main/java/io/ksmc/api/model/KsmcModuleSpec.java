package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class KsmcModuleSpec {
    /** {@code Jar} : chargé dans le controller. {@code Container} : réservé aux futurs modules conteneurs. */
    public enum Type { Jar, Container }

    @Default("Jar")
    public Type type = Type.Jar;
    @Required
    public Source source;
    @Default("true")
    public boolean enabled = true;
    /** Configuration transmise au module, rechargée à chaud. */
    @PreserveUnknownFields
    public ObjectNode config;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @ValidationRule(value = "[has(self.url), has(self.maven), has(self.store), has(self.builtin)].filter(x, x).size() == 1",
            message = "exactly one module source must be set")
    @ValidationRule(value = "!has(self.url) || has(self.sha256)", message = "sha256 is required with url")
    public static class Source {
        /** URL HTTPS du JAR (sha256 obligatoire). */
        @Pattern("^https://.+")
        public String url;
        @Pattern("^[a-f0-9]{64}$")
        public String sha256;
        /** Coordonnées {@code groupe:artefact:version}. */
        public String maven;
        public String mavenRepository;
        /** Chemin d'un JAR dans le Template Store. */
        public String store;
        /** Id d'un module intégré au controller. */
        public String builtin;
    }
}
