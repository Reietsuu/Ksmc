package io.ksmc.controller.module;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Classloader d'un module : API publique (parent), puis le JAR du module, puis les JAR de ses dépendances déclarées
 * (non transitives). Deux modules peuvent ainsi embarquer des versions différentes d'une même bibliothèque.
 */
final class ModuleClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final List<ModuleClassLoader> dependencies;

    ModuleClassLoader(String moduleId, URL jar, ClassLoader api, List<ModuleClassLoader> dependencies) {
        super("module-" + moduleId, new URL[] {jar}, api);
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = getParent().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // Pas dans l'API : classes du module puis des dépendances.
                }
            }
            if (c == null) {
                c = findOwn(name);
            }
            for (int i = 0; c == null && i < dependencies.size(); i++) {
                c = dependencies.get(i).findOwn(name);
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /** Classe définie par ce seul JAR, ou {@code null}. */
    Class<?> findOwn(String name) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c;
            }
            try {
                return findClass(name);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = super.getResource(name);
        for (int i = 0; url == null && i < dependencies.size(); i++) {
            url = dependencies.get(i).findResource(name);
        }
        return url;
    }
}
