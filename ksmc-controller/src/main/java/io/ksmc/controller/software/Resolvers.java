package io.ksmc.controller.software;

import io.ksmc.api.model.MinecraftVersions;
import io.ksmc.api.model.SoftwareDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilitaires communs aux résolveurs intégrés. */
final class Resolvers {
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>([^<]+)</version>");
    static final Pattern UNSTABLE = Pattern.compile("(?i).*(snapshot|pre|rc|beta|alpha).*");

    private Resolvers() {
    }

    /** Versions listées dans un maven-metadata.xml, triées de la plus récente à la plus ancienne. */
    static List<String> mavenVersions(String metadataXml) {
        List<String> out = new ArrayList<>();
        Matcher m = MAVEN_VERSION.matcher(metadataXml);
        while (m.find()) {
            out.add(m.group(1));
        }
        out.sort(MinecraftVersions.COMPARATOR.reversed());
        return out;
    }

    /** Java pour construire/lancer une version, selon les règles de la définition (21 par défaut). */
    static int javaFor(SoftwareDefinition def, String minecraftVersion) {
        return MinecraftVersions.javaFor(minecraftVersion, def.getSpec().javaVersions, 21);
    }

    static String param(SoftwareDefinition def, String key, String defaultValue) {
        return def.getSpec().resolver.params.getOrDefault(key, defaultValue);
    }
}
