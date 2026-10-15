package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Required;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SoftwareDefinitionSpec {
    /** Famille utilisée pour choisir le patch de configuration et le bridge. */
    public enum Family {
        @JsonProperty("paper") PAPER,
        @JsonProperty("spigot") SPIGOT,
        @JsonProperty("velocity") VELOCITY,
        @JsonProperty("bungeecord") BUNGEECORD,
        @JsonProperty("fabric") FABRIC,
        @JsonProperty("forge") FORGE,
        @JsonProperty("neoforge") NEOFORGE,
        @JsonProperty("generic") GENERIC
    }

    @Required
    public Environment environment;
    @Required
    public Family family;
    @Required
    public Resolver resolver;
    /** Version Java par plage de versions Minecraft, évaluée dans l'ordre. */
    public List<JavaVersionRule> javaVersions = new ArrayList<>();
    public Launch launch;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Resolver {
        /** Type de résolveur : url, papermc, jenkins, buildtools, fabric-meta, forge-installer, neoforge-installer, ou fourni par un module. */
        @Required
        public String type;
        /** Paramètres propres au résolveur (ex. {@code project: paper}, {@code url: https://.../{version}.jar}). */
        public Map<String, String> params = new LinkedHashMap<>();
    }

    public static class JavaVersionRule {
        /** Plage inclusive {@code min-max} ou {@code min+}, ex. {@code 1.8-1.16.5}, {@code 1.20.5+}. */
        @Required
        public String minecraft;
        @Required
        public int java;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Launch {
        public List<String> jvmOptions = new ArrayList<>();
        public List<String> args = new ArrayList<>();
        /** Commande d'arrêt propre (défaut {@code stop}, {@code end} pour BungeeCord). */
        public String stopCommand;
    }
}
