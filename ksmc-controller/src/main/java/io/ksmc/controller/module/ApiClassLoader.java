package io.ksmc.controller.module;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Parent commun des classloaders de modules : le JDK, plus les seuls paquets de l'API publique pris dans le
 * classloader du controller. Les classes internes du controller et ses bibliothèques restent invisibles.
 */
final class ApiClassLoader extends ClassLoader {

    /** API Ksmc, messagerie, journalisation, JSON et client Kubernetes (types présents dans {@code ModuleContext}). */
    static final List<String> EXPORTED = List.of("io.ksmc.api.", "io.ksmc.wire.", "org.slf4j.", "com.fasterxml.jackson.",
            "io.fabric8.");

    private final ClassLoader host;

    ApiClassLoader(ClassLoader host) {
        super("ksmc-module-api", ClassLoader.getPlatformClassLoader());
        this.host = host;
    }

    static boolean exported(String className) {
        for (String prefix : EXPORTED) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exportedResource(String path) {
        return exported(path.replace('/', '.'));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (exported(name)) {
            return host.loadClass(name);
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        return exportedResource(name) ? host.getResource(name) : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        return exportedResource(name) ? host.getResources(name) : Collections.emptyEnumeration();
    }
}
