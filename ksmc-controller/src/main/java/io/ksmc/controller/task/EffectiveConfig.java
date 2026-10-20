package io.ksmc.controller.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ksmc.api.model.CloudServiceSpec;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.RuntimeConfig;
import io.ksmc.api.model.ServiceGroup;
import io.ksmc.api.model.ServiceTask;
import io.ksmc.api.model.ServiceTaskSpec;
import io.ksmc.api.model.TemplateRef;
import io.ksmc.controller.service.StrategicMerge;
import io.ksmc.controller.template.Digests;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration effective d'une task après fusion de ses groupes, et spec des {@code CloudService} qui en découlent.
 *
 * <p>Ordre : groupes d'environnement (triés par nom) → groupes référencés (ordre de la task) → task.
 * Listes concaténées, maps fusionnées (le plus spécifique gagne), {@code podTemplate} en strategic merge.
 */
public record EffectiveConfig(List<String> groups, CloudServiceSpec serviceSpec, String hash) {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Propriétés modifiables sans redémarrer les services (hors du hash de configuration). */
    public static final List<String> HOT_PROPERTIES = List.of("ksmc.maintenance");

    /** Un groupe référencé par la task n'existe pas. */
    public static final class GroupNotFoundException extends Exception {
        public GroupNotFoundException(String group) {
            super("ServiceGroup '" + group + "' not found");
        }
    }

    public static EffectiveConfig resolve(ServiceTask task, Map<String, ServiceGroup> groupsByName)
            throws GroupNotFoundException {
        ServiceTaskSpec spec = task.getSpec();
        Set<String> order = new LinkedHashSet<>();
        groupsByName.values().stream()
                .filter(g -> g.getSpec() != null && g.getSpec().targetEnvironments.contains(spec.environment))
                .map(g -> g.getMetadata().getName())
                .sorted(Comparator.naturalOrder())
                .forEach(order::add);
        for (String name : spec.groups) {
            if (!groupsByName.containsKey(name)) {
                throw new GroupNotFoundException(name);
            }
            order.add(name);
        }

        CloudServiceSpec out = new CloudServiceSpec();
        for (String name : order) {
            mergeRuntime(out, groupsByName.get(name).getSpec());
        }
        mergeRuntime(out, spec);

        out.task = task.getMetadata().getName();
        out.environment = spec.environment;
        out.software = spec.software;
        out.memoryMb = spec.memoryMb;
        out.resources = spec.resources;
        out.staticService = spec.staticServices;
        out.storage = spec.storage;
        out.stopTimeoutSeconds = spec.stopTimeoutSeconds;
        out.deployOnStop = spec.deployOnStop;
        out.bridge = spec.bridge;
        // Statique : redémarré sous le même nom par le service lui-même. Dynamique : remplacé par la task.
        out.restartPolicy = spec.staticServices ? CloudServiceSpec.RestartPolicy.Always : CloudServiceSpec.RestartPolicy.Never;
        // Réglages lus par les bridges (propriétés du service).
        if (spec.maintenance) {
            out.properties.put("ksmc.maintenance", "true");
        }
        if (spec.fallback != null && spec.fallback.enabled) {
            out.properties.put("ksmc.fallback", "true");
            out.properties.put("ksmc.fallback.priority", String.valueOf(spec.fallback.priority));
            if (spec.fallback.permission != null) {
                out.properties.put("ksmc.fallback.permission", spec.fallback.permission);
            }
        }
        spec.forcedHosts.forEach((host, target) ->
                out.properties.put("ksmc.forced-host." + host.toLowerCase(java.util.Locale.ROOT), target));
        // Les réglages à chaud (maintenance) sont appliqués aux services existants sans les recréer.
        com.fasterxml.jackson.databind.node.ObjectNode hashed = JSON.valueToTree(out);
        if (hashed.get("properties") instanceof com.fasterxml.jackson.databind.node.ObjectNode props) {
            HOT_PROPERTIES.forEach(props::remove);
            if (props.isEmpty()) {
                hashed.remove("properties"); // même JSON que sans la propriété (champ vide omis)
            }
        }
        String hash = Digests.sha256(hashed.toString()).substring(0, 16);
        return new EffectiveConfig(new ArrayList<>(order), out, hash);
    }

    /** Ajoute une couche de configuration (groupe ou task) par-dessus l'accumulateur. */
    static void mergeRuntime(RuntimeConfig acc, RuntimeConfig layer) {
        if (layer == null) {
            return;
        }
        for (TemplateRef ref : layer.templates) {
            acc.templates.removeIf(t -> t.name.equals(ref.name));
            acc.templates.add(ref);
        }
        acc.jvmOptions.addAll(layer.jvmOptions);
        acc.processArgs.addAll(layer.processArgs);
        acc.env.putAll(layer.env);
        acc.properties.putAll(layer.properties);
        if (layer.podTemplate != null) {
            acc.podTemplate = StrategicMerge.merge(acc.podTemplate == null ? StrategicMerge.emptyObject()
                    : acc.podTemplate, layer.podTemplate);
        }
        for (Map.Entry<String, JsonNode> e : layer.extensions.entrySet()) {
            JsonNode current = acc.extensions.get(e.getKey());
            if (current instanceof ObjectNode obj && e.getValue().isObject()) {
                StrategicMerge.merge(obj, e.getValue());
            } else {
                acc.extensions.put(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    /** Templates effectifs (noms), pour le statut de la task. */
    public List<String> templateNames() {
        List<String> names = new ArrayList<>();
        serviceSpec.templates.forEach(t -> names.add(t.name));
        return names;
    }

    static Map<String, ServiceGroup> index(List<ServiceGroup> groups) {
        Map<String, ServiceGroup> map = new LinkedHashMap<>();
        groups.forEach(g -> map.put(g.getMetadata().getName(), g));
        return map;
    }

    public static boolean targets(ServiceGroup group, Environment env) {
        return group.getSpec() != null && group.getSpec().targetEnvironments.contains(env);
    }
}
