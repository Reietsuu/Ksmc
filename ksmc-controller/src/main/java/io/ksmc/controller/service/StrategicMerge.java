package io.ksmc.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Fusion de fragments de Pod inspirée du strategic merge patch de Kubernetes :
 * objets fusionnés récursivement, listes d'éléments nommés ({@code containers}, {@code volumes}, {@code env},
 * {@code ports}, {@code volumeMounts}...) fusionnées par clé, autres listes remplacées, {@code null} supprime.
 */
public final class StrategicMerge {
    /** Listes fusionnées par clé : nom du champ → clé de fusion. */
    private static final Map<String, String> MERGE_KEYS = Map.ofEntries(
            Map.entry("containers", "name"),
            Map.entry("initContainers", "name"),
            Map.entry("ephemeralContainers", "name"),
            Map.entry("volumes", "name"),
            Map.entry("env", "name"),
            Map.entry("ports", "containerPort"),
            Map.entry("volumeMounts", "mountPath"),
            Map.entry("imagePullSecrets", "name"),
            Map.entry("tolerations", "key"),
            Map.entry("hostAliases", "ip"));
    /** Listes de valeurs simples concaténées sans doublon. */
    private static final Set<String> SET_LISTS = Set.of("finalizers");

    private StrategicMerge() {
    }

    /** Fusionne {@code patch} dans {@code base} (modifié en place) et renvoie {@code base}. */
    public static ObjectNode merge(ObjectNode base, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return base;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            JsonNode value = e.getValue();
            JsonNode current = base.get(key);
            if (value == null || value.isNull()) {
                base.remove(key);
            } else if (value.isObject() && current != null && current.isObject()) {
                merge((ObjectNode) current, value);
            } else if (value.isArray() && current != null && current.isArray() && MERGE_KEYS.containsKey(key)) {
                mergeList((ArrayNode) current, (ArrayNode) value, MERGE_KEYS.get(key));
            } else if (value.isArray() && current != null && current.isArray() && SET_LISTS.contains(key)) {
                value.forEach(v -> {
                    if (!contains((ArrayNode) current, v)) {
                        ((ArrayNode) current).add(v);
                    }
                });
            } else {
                base.set(key, value.deepCopy());
            }
        }
        return base;
    }

    private static void mergeList(ArrayNode base, ArrayNode patch, String mergeKey) {
        for (JsonNode item : patch) {
            JsonNode id = item.get(mergeKey);
            ObjectNode existing = null;
            if (id != null) {
                for (JsonNode b : base) {
                    if (b.isObject() && id.equals(b.get(mergeKey))) {
                        existing = (ObjectNode) b;
                        break;
                    }
                }
            }
            if (existing != null && item.isObject()) {
                merge(existing, item);
            } else {
                base.add(item.deepCopy());
            }
        }
    }

    private static boolean contains(ArrayNode array, JsonNode v) {
        for (JsonNode n : array) {
            if (n.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }
}
