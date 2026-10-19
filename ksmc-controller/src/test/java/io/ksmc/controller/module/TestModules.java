package io.ksmc.controller.module;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Construit de vrais JAR de modules (sources compilées à la volée) pour les tests. */
final class TestModules {

    private TestModules() {
    }

    /**
     * @param sources    nom de classe qualifié → source Java
     * @param classpath  JAR supplémentaires (dépendances de module)
     */
    static Path jar(Path dir, String descriptorJson, Map<String, String> sources, Path... classpath) throws IOException {
        Path src = Files.createTempDirectory(dir, "src");
        Path out = Files.createTempDirectory(dir, "classes");
        List<String> files = new ArrayList<>();
        for (var e : sources.entrySet()) {
            Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
        for (Path p : classpath) {
            cp.append(java.io.File.pathSeparator).append(p);
        }
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", cp.toString(), "--release", "21"));
        args.addAll(files);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("compilation du module de test échouée");
        }
        Path jar = Files.createTempFile(dir, "module", ".jar");
        try (OutputStream fos = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(fos)) {
            if (descriptorJson != null) {
                jos.putNextEntry(new JarEntry("ksmc-module.json"));
                jos.write(descriptorJson.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            try (Stream<Path> walk = Files.walk(out)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                    jos.write(Files.readAllBytes(p));
                    jos.closeEntry();
                }
            }
        }
        return jar;
    }

    static String descriptor(String id, String version, String main, String extra) {
        return "{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"main\":\"" + main + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }
}
