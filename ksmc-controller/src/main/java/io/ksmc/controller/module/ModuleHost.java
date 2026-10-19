package io.ksmc.controller.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.module.KsmcApiVersion;
import io.ksmc.api.module.KsmcModule;
import io.ksmc.api.module.ModuleContext;
import io.ksmc.api.module.ModuleDescriptor;
import io.ksmc.api.module.SemVer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Charge, active et désactive les modules. Chaque module a son classloader et son scope dans l'
 * {@link ExtensionRegistry} ; toute erreur d'un module est confinée et remontée en {@link ModuleException}.
 */
public final class ModuleHost {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleHost.class);

    private final ExtensionRegistry registry;
    private final ModuleContextFactory contexts;
    private final ApiClassLoader api;
    private final SemVer apiVersion;
    private final Map<String, Active> active = new LinkedHashMap<>();

    private static final class Active {
        final ModuleDescriptor descriptor;
        final ModuleClassLoader loader;
        final KsmcModule module;
        volatile JsonNode config;

        Active(ModuleDescriptor descriptor, ModuleClassLoader loader, KsmcModule module, JsonNode config) {
            this.descriptor = descriptor;
            this.loader = loader;
            this.module = module;
            this.config = config;
        }
    }

    public ModuleHost(ExtensionRegistry registry, ModuleContextFactory contexts) {
        this(registry, contexts, KsmcApiVersion.CURRENT);
    }

    ModuleHost(ExtensionRegistry registry, ModuleContextFactory contexts, SemVer apiVersion) {
        this.registry = registry;
        this.contexts = contexts;
        this.api = new ApiClassLoader(ModuleHost.class.getClassLoader());
        this.apiVersion = apiVersion;
    }

    /** Lit et valide {@code ksmc-module.json} d'un JAR. */
    public static ModuleDescriptor readDescriptor(Path jar) throws ModuleException {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(ModuleDescriptor.FILE);
            if (entry == null) {
                throw new ModuleException(ModuleException.INVALID_DESCRIPTOR, ModuleDescriptor.FILE + " absent du JAR");
            }
            try (InputStream in = file.getInputStream(entry)) {
                return ModuleDescriptor.read(in);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ModuleException(ModuleException.INVALID_DESCRIPTOR, "descripteur invalide : " + e.getMessage(), e);
        }
    }

    /** Versions des modules actifs (pour {@link ModuleGraph}). */
    public synchronized Map<String, SemVer> activeVersions() {
        Map<String, SemVer> out = new LinkedHashMap<>();
        active.forEach((id, a) -> out.put(id, a.descriptor.semVer()));
        return out;
    }

    public synchronized Optional<ModuleDescriptor> descriptor(String id) {
        Active a = active.get(id);
        return a == null ? Optional.empty() : Optional.of(a.descriptor);
    }

    /**
     * Charge et active le module du JAR. Un module actif du même id est d'abord désactivé (rechargement), ainsi que
     * les modules qui en dépendent : leurs ids sont renvoyés pour qu'ils soient réactivés.
     *
     * @param expectedId id attendu (celui de la ressource), ou {@code null}
     */
    public synchronized EnableResult enable(Path jar, JsonNode config, String expectedId) throws ModuleException {
        ModuleDescriptor d = readDescriptor(jar);
        if (expectedId != null && !expectedId.equals(d.id)) {
            throw new ModuleException(ModuleException.INVALID_DESCRIPTOR,
                    "le JAR contient le module " + d.id + " et non " + expectedId);
        }
        if (!d.compatibleWith(apiVersion)) {
            throw new ModuleException(ModuleException.INCOMPATIBLE_API,
                    "le module requiert l'API " + d.apiRange() + ", le controller fournit " + apiVersion);
        }
        List<String> cascaded = active.containsKey(d.id) ? disable(d.id) : List.of();
        Map<String, SemVer> available = activeVersions();
        ModuleException blocked = ModuleGraph.order(List.of(d), available).failed().get(d.id);
        if (blocked != null) {
            throw blocked;
        }
        List<ModuleClassLoader> deps = new ArrayList<>();
        for (ModuleDescriptor.Dependency dep : d.dependencies) {
            Active a = active.get(dep.id);
            if (a != null) {
                deps.add(a.loader);
            }
        }
        ModuleClassLoader loader;
        KsmcModule module;
        try {
            loader = new ModuleClassLoader(d.id, jar.toUri().toURL(), api, deps);
        } catch (IOException e) {
            throw new ModuleException(ModuleException.LOAD_ERROR, e.getMessage(), e);
        }
        try {
            Class<?> main = Class.forName(d.main, true, loader);
            if (!KsmcModule.class.isAssignableFrom(main)) {
                throw new ModuleException(ModuleException.LOAD_ERROR, d.main + " n'implémente pas KsmcModule");
            }
            module = withLoader(loader, () -> (KsmcModule) main.getDeclaredConstructor().newInstance());
        } catch (ModuleException e) {
            closeQuietly(loader);
            throw e;
        } catch (Throwable e) {
            closeQuietly(loader);
            throw new ModuleException(ModuleException.LOAD_ERROR, "chargement de " + d.main + " : " + describe(e), e);
        }
        Active a = new Active(d, loader, module, config == null ? JsonNodeFactory.instance.objectNode() : config);
        ModuleContext context = contexts.create(d, registry.scope(d.id), () -> a.config);
        try {
            withLoader(loader, () -> {
                module.onEnable(context);
                return null;
            });
        } catch (Throwable e) {
            registry.closeScope(d.id);
            closeQuietly(loader);
            throw new ModuleException(ModuleException.ENABLE_ERROR, describe(e), e);
        }
        active.put(d.id, a);
        LOG.info("Module {} {} activé", d.id, d.version);
        return new EnableResult(d, cascaded);
    }

    public record EnableResult(ModuleDescriptor descriptor, List<String> disabledDependents) {
    }

    /** Transmet une nouvelle configuration au module actif. */
    public synchronized void configure(String id, JsonNode config) throws ModuleException {
        Active a = active.get(id);
        if (a == null) {
            return;
        }
        JsonNode value = config == null ? JsonNodeFactory.instance.objectNode() : config;
        if (value.equals(a.config)) {
            return;
        }
        a.config = value;
        try {
            withLoader(a.loader, () -> {
                a.module.onConfigChanged(value);
                return null;
            });
        } catch (Throwable e) {
            throw new ModuleException(ModuleException.CONFIG_ERROR, describe(e), e);
        }
    }

    /** Désactive le module et, d'abord, ceux qui en dépendent ; renvoie les ids des dépendants désactivés. */
    public synchronized List<String> disable(String id) {
        List<String> dependents = new ArrayList<>();
        if (!active.containsKey(id)) {
            return dependents;
        }
        for (Active other : List.copyOf(active.values())) {
            boolean dependsOn = other.descriptor.dependencies.stream().anyMatch(dep -> dep.id.equals(id));
            if (dependsOn && active.containsKey(other.descriptor.id)) {
                dependents.addAll(disable(other.descriptor.id));
                dependents.add(other.descriptor.id);
            }
        }
        Active a = active.remove(id);
        try {
            withLoader(a.loader, () -> {
                a.module.onDisable();
                return null;
            });
        } catch (Throwable e) {
            LOG.warn("Module {} : erreur à la désactivation : {}", id, describe(e));
        }
        registry.closeScope(id);
        closeQuietly(a.loader);
        LOG.info("Module {} désactivé", id);
        return dependents;
    }

    public synchronized void disableAll() {
        for (String id : List.copyOf(active.keySet()).reversed()) {
            disable(id);
        }
    }

    private static <T> T withLoader(ClassLoader loader, Callable<T> action) throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            t.setContextClassLoader(previous);
        }
    }

    private static String describe(Throwable e) {
        if (e instanceof VirtualMachineError vm) {
            throw vm;
        }
        if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
            e = e.getCause();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static void closeQuietly(ModuleClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.debug("Fermeture du classloader : {}", e.getMessage());
        }
    }
}
