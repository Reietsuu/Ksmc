package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@ValidationRule(value = "[has(self.storage), has(self.git), has(self.http), has(self.configMap), has(self.image)]"
        + ".filter(x, x).size() == 1", message = "exactly one template source must be set")
public class ServiceTemplateSpec implements Extensible {
    public Storage storage;
    public Git git;
    public Http http;
    public ConfigMapSource configMap;
    public Image image;
    /** Sources fournies par des modules : {@code custom.type} + paramètres libres. */
    @PreserveUnknownFields
    public Map<String, JsonNode> extensions = new LinkedHashMap<>();

    @Override
    public Map<String, JsonNode> extensions() {
        return extensions;
    }

    public static class Storage {
        /** Chemin dans le Template Store : {@code <préfixe>/<nom>}. */
        @Required
        public String path;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Git {
        @Required
        public String url;
        public String ref;
        /** Sous-dossier du dépôt à utiliser. */
        public String path;
        /** Secret contenant {@code username}/{@code password} ou {@code ssh-privatekey}. */
        public String secretRef;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Http {
        /** Archive tar.gz ou zip. */
        @Required
        public String url;
        public String sha256;
    }

    public static class ConfigMapSource {
        @Required
        public String name;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Image {
        @Required
        public String image;
        /** Dossier de l'image copié dans le service (défaut {@code /template}). */
        public String path;
    }
}
