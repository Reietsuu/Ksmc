package io.ksmc.controller.module;

import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.SemVer;
import io.ksmc.api.module.VersionRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordre d'activation d'un ensemble de modules selon leurs dépendances (tri topologique). */
public final class ModuleGraph {

    /** Modules activables dans l'ordre, et modules écartés avec la raison. */
    public record Result(List<ModuleDescriptor> order, Map<String, ModuleException> failed) {
    }

    private ModuleGraph() {
    }

    /**
     * @param candidates modules à activer
     * @param available  modules déjà actifs (id → version), utilisables comme dépendances
     */
    public static Result order(List<ModuleDescriptor> candidates, Map<String, SemVer> available) {
        Map<String, ModuleDescriptor> byId = new LinkedHashMap<>();
        Map<String, ModuleException> failed = new LinkedHashMap<>();
        for (ModuleDescriptor d : candidates) {
            if (byId.containsKey(d.id) || available.containsKey(d.id)) {
                failed.put(d.id, new ModuleException(ModuleException.DUPLICATE_ID, "id de module en double : " + d.id));
            } else {
                byId.put(d.id, d);
            }
        }
        failed.keySet().forEach(byId::remove);

        List<ModuleDescriptor> order = new ArrayList<>();
        Map<String, SemVer> ready = new LinkedHashMap<>(available);
        boolean progress = true;
        while (!byId.isEmpty() && progress) {
            progress = false;
            for (var it = byId.values().iterator(); it.hasNext(); ) {
                ModuleDescriptor d = it.next();
                ModuleException missing = null;
                boolean waiting = false;
                for (ModuleDescriptor.Dependency dep : d.dependencies) {
                    SemVer v = ready.get(dep.id);
                    if (v != null) {
                        if (!VersionRange.parse(dep.version).matches(v) && !dep.optional) {
                            missing = new ModuleException(ModuleException.MISSING_DEPENDENCY,
                                    "dépendance " + dep.id + " " + v + " hors de la plage " + dep.version);
                        }
                    } else if (byId.containsKey(dep.id)) {
                        waiting = true;
                    } else if (!dep.optional) {
                        ModuleException cause = failed.get(dep.id);
                        missing = new ModuleException(ModuleException.MISSING_DEPENDENCY, "dépendance manquante : " + dep.id
                                + (cause == null ? "" : " (" + cause.reason() + ")"));
                    }
                }
                if (missing != null) {
                    failed.put(d.id, missing);
                    it.remove();
                    progress = true;
                } else if (!waiting) {
                    order.add(d);
                    ready.put(d.id, d.semVer());
                    it.remove();
                    progress = true;
                }
            }
        }
        for (ModuleDescriptor d : byId.values()) {
            failed.put(d.id, new ModuleException(ModuleException.DEPENDENCY_CYCLE,
                    "cycle de dépendances impliquant " + d.id));
        }
        return new Result(order, failed);
    }
}
