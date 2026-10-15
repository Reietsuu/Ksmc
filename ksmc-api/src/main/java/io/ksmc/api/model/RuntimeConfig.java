package io.ksmc.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.crd.generator.annotation.PreserveUnknownFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration d'exécution partagée par les groupes, les tasks et les services
 * (fusionnée dans l'ordre groupes d'environnement → groupes référencés → task).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public abstract class RuntimeConfig implements Extensible {
    /** Templates appliqués dans l'ordre. */
    public List<TemplateRef> templates = new ArrayList<>();
    /** Options JVM supplémentaires. */
    public List<String> jvmOptions = new ArrayList<>();
    /** Arguments passés au logiciel serveur. */
    public List<String> processArgs = new ArrayList<>();
    /** Variables d'environnement du conteneur. */
    public Map<String, String> env = new LinkedHashMap<>();
    /** Propriétés libres exposées aux plugins et au bridge. */
    public Map<String, String> properties = new LinkedHashMap<>();
    /**
     * Fragment de PodTemplateSpec Kubernetes fusionné (strategic merge) dans le Pod du service :
     * ressources, nodeSelector, tolérances, affinités, volumes, sidecars...
     */
    @PreserveUnknownFields
    public ObjectNode podTemplate;
    /** Réglages libres de modules, par id de module. */
    @PreserveUnknownFields
    public Map<String, JsonNode> extensions = new LinkedHashMap<>();

    @Override
    public Map<String, JsonNode> extensions() {
        return extensions;
    }
}
