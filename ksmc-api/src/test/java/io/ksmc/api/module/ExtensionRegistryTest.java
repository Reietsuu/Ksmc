package io.ksmc.api.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {

    interface Greeter {
        String greet();
    }

    @Test
    void closingAScopeRemovesItsRegistrationsOnly() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.scope("a").register(Greeter.class, () -> "a");
        registry.scope("b").register(Greeter.class, () -> "b");
        assertThat(registry.all(Greeter.class)).extracting(Greeter::greet).containsExactly("a", "b");

        registry.closeScope("a");

        assertThat(registry.all(Greeter.class)).extracting(Greeter::greet).containsExactly("b");
        assertThat(registry.count("a")).isZero();
    }

    @Test
    void resourcesAreClosedInReverseOrderAndErrorsAreContained() {
        List<String> closed = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        ExtensionRegistry registry = new ExtensionRegistry(errors::add);
        Registry scope = registry.scope("m");
        scope.onDisable(() -> closed.add("first"));
        scope.onDisable(() -> {
            throw new IllegalStateException("boom");
        });
        scope.onDisable(() -> closed.add("last"));

        registry.closeScope("m");

        assertThat(closed).containsExactly("last", "first");
        assertThat(errors).singleElement().satisfies(e -> assertThat(e).hasMessage("boom"));
    }

    @Test
    void earlyCloseAndRepeatedEnableDisableLeaveNothingBehind() {
        ExtensionRegistry registry = new ExtensionRegistry();
        Registry.Registration r = registry.scope("m").register(Greeter.class, () -> "x");
        r.close();
        r.close();
        assertThat(registry.all(Greeter.class)).isEmpty();
        assertThat(registry.count("m")).isZero();

        for (int i = 0; i < 20; i++) {
            registry.scope("m").register(Greeter.class, () -> "x");
            registry.scope("m").onDisable(() -> { });
            registry.closeScope("m");
        }
        assertThat(registry.all(Greeter.class)).isEmpty();
        assertThat(registry.count("m")).isZero();
    }

    @Test
    void bindingsSeeExistingAndFutureImplementations() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.scope("a").register(Greeter.class, () -> "a");
        List<String> live = new ArrayList<>();
        registry.bind(Greeter.class, g -> live.add(g.greet()), g -> live.remove(g.greet()));
        assertThat(live).containsExactly("a");

        registry.scope("b").register(Greeter.class, () -> "b");
        assertThat(live).containsExactly("a", "b");

        registry.closeScope("a");
        assertThat(live).containsExactly("b");
    }
}
