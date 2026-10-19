package io.ksmc.controller.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.SemVer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModuleGraphTest {

    static ModuleDescriptor d(String id, String... deps) {
        ModuleDescriptor d = new ModuleDescriptor();
        d.id = id;
        d.version = "1.0.0";
        d.main = "x.Main";
        for (String dep : deps) {
            ModuleDescriptor.Dependency x = new ModuleDescriptor.Dependency();
            String[] parts = dep.split(":");
            x.id = parts[0].replace("?", "");
            x.optional = parts[0].endsWith("?");
            x.version = parts.length > 1 ? parts[1] : null;
            d.dependencies.add(x);
        }
        return d;
    }

    static List<String> ids(ModuleGraph.Result r) {
        return r.order().stream().map(m -> m.id).toList();
    }

    @Test
    void dependenciesComeFirst() {
        ModuleGraph.Result r = ModuleGraph.order(List.of(d("c", "b"), d("b", "a"), d("a")), Map.of());
        assertThat(ids(r)).containsExactly("a", "b", "c");
        assertThat(r.failed()).isEmpty();
    }

    @Test
    void missingRequiredDependencyFailsTransitively() {
        ModuleGraph.Result r = ModuleGraph.order(List.of(d("b", "missing"), d("c", "b"), d("ok", "gone?")), Map.of());
        assertThat(ids(r)).containsExactly("ok");
        assertThat(r.failed()).containsOnlyKeys("b", "c");
        assertThat(r.failed().get("c").reason()).isEqualTo(ModuleException.MISSING_DEPENDENCY);
        assertThat(r.failed().get("c").getMessage()).contains("b");
    }

    @Test
    void versionRangesAreCheckedAgainstActiveModules() {
        Map<String, SemVer> active = Map.of("base", SemVer.parse("1.4.0"));
        ModuleGraph.Result r = ModuleGraph.order(List.of(d("good", "base:^1.2"), d("bad", "base:^2.0"),
                d("soft", "base?:^2.0")), active);
        assertThat(ids(r)).containsExactly("good", "soft");
        assertThat(r.failed()).containsOnlyKeys("bad");
    }

    @Test
    void cyclesAndDuplicatesAreReported() {
        ModuleGraph.Result r = ModuleGraph.order(List.of(d("x", "y"), d("y", "x"), d("dup"), d("dup")),
                Map.of("active", SemVer.parse("1.0.0")));
        assertThat(r.failed().get("x").reason()).isEqualTo(ModuleException.DEPENDENCY_CYCLE);
        assertThat(r.failed().get("y").reason()).isEqualTo(ModuleException.DEPENDENCY_CYCLE);
        assertThat(r.failed().get("dup").reason()).isEqualTo(ModuleException.DUPLICATE_ID);
        assertThat(ids(r)).isEmpty();
    }
}
