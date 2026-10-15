package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Max;
import io.fabric8.generator.annotation.Min;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.ValidationRule;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.model.annotation.SpecReplicas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@ValidationRule(value = "self.maxServiceCount == -1 || self.maxServiceCount >= self.minServiceCount",
        message = "maxServiceCount must be -1 or >= minServiceCount")
public class ServiceTaskSpec extends RuntimeConfig {
    @Required
    public Environment environment;
    @Required
    public SoftwareRef software;
    /** Groupes appliqués, dans l'ordre. */
    public List<String> groups = new ArrayList<>();
    /** Mémoire maximale de la JVM (Mo). */
    @Min(256)
    @Default("1024")
    public int memoryMb = 1024;
    /** Ressources Kubernetes du conteneur ; par défaut dérivées de {@link #memoryMb}. */
    public ResourceRequirements resources;
    @Min(0)
    @Default("1")
    @SpecReplicas
    public int minServiceCount = 1;
    /** -1 = illimité. */
    @Min(-1)
    @Default("-1")
    public int maxServiceCount = -1;
    /** Services démarrés manuellement en plus du minimum. */
    @Min(0)
    @Default("0")
    public int extraServices;
    /** Services statiques : nom et données persistants. */
    @JsonProperty("static")
    public boolean staticServices;
    @Default("true")
    public boolean enabled = true;
    public boolean maintenance;
    @Min(1)
    @Max(3600)
    @Default("30")
    public int stopTimeoutSeconds = 30;
    public UpdateStrategy updateStrategy;
    public StorageSpec storage;
    public DeployOnStop deployOnStop;
    /** Serveurs : participation au routage fallback/lobby des proxies. */
    public Fallback fallback;
    /** Proxies : exposition hors du cluster. */
    public Expose expose;
    /** Proxies : nom d'hôte → task serveur cible. */
    public Map<String, String> forcedHosts = new LinkedHashMap<>();
    public Bridge bridge;
    public Autoscaling autoscaling;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateStrategy {
        public enum Type { Recreate, RollingUpdate, OnRestart }

        @Default("RollingUpdate")
        public Type type = Type.RollingUpdate;
        @Min(1)
        @Default("1")
        public int maxUnavailable = 1;
        @Min(30)
        @Default("600")
        public int progressDeadlineSeconds = 600;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StorageSpec {
        @Default("2Gi")
        public String size = "2Gi";
        public String storageClassName;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DeployOnStop {
        /** Template {@code storage} cible ({@code <préfixe>/<nom>}). */
        @Required
        public String template;
        /** Motifs glob exclus. */
        public List<String> excludes = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Fallback {
        public boolean enabled;
        @Default("0")
        public int priority;
        /** Permission requise pour être envoyé sur ce fallback. */
        public String permission;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Expose {
        public enum Type { LoadBalancer, NodePort, ClusterIP }

        @Default("LoadBalancer")
        public Type type = Type.LoadBalancer;
        @Min(1)
        @Max(65535)
        @Default("25565")
        public int port = 25565;
        @Min(30000)
        @Max(32767)
        public Integer nodePort;
        public Map<String, String> annotations = new LinkedHashMap<>();
        public Map<String, String> labels = new LinkedHashMap<>();
        public String externalTrafficPolicy;
        public List<String> loadBalancerSourceRanges = new ArrayList<>();
        public boolean proxyProtocol;
        /** Nombre maximal de joueurs affiché par le ping global. */
        public Integer maxPlayers;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Bridge {
        @Default("true")
        public boolean enabled = true;
        /** Mode de forwarding pour les serveurs moddés : modern, legacy ou none. */
        public String forwarding;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Autoscaling {
        /** Stratégies combinées (cible = maximum) : static, players, schedule, ou fournie par un module. */
        public List<String> strategies = new ArrayList<>();
        @Min(0.05)
        @Max(1.0)
        public Double scaleUpThreshold;
        @Min(0)
        public Integer idleStopSeconds;
        @Min(0)
        public Integer cooldownSeconds;
        @Min(1)
        public Integer maxConcurrentStarts;
        @Min(0)
        public Integer preparedServices;
        public List<ScheduleRule> schedule = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScheduleRule {
        /** Expression cron Unix de début de plage. */
        @Required
        public String from;
        /** Expression cron Unix de fin de plage. */
        @Required
        public String to;
        @Default("UTC")
        public String timeZone = "UTC";
        @Min(0)
        public int minServiceCount;
    }
}
