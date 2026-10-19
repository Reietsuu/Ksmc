package io.ksmc.api.module;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Contenu de {@code ksmc-module.json} à la racine du JAR :
 * <pre>{@code
 * {"id": "hello", "name": "Hello", "version": "1.0.0", "main": "com.example.HelloModule",
 *  "apiVersion": "^1.0", "dependencies": [{"id": "other", "version": "^2.1", "optional": true}]}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModuleDescriptor {

    public static final String FILE = "ksmc-module.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Identifiant : minuscules, chiffres et tirets (préfixe des routes REST et de la CLI). */
    public String id;
    public String name;
    public String version;
    public String description;
    /** Classe implémentant {@link KsmcModule}. */
    public String main;
    /** Plage de versions de l'API Ksmc acceptée (défaut : la version majeure courante). */
    public String apiVersion;
    public List<Dependency> dependencies = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dependency {
        public String id;
        /** Plage de versions acceptée (défaut {@code *}). */
        public String version;
        public boolean optional;
    }

    public static ModuleDescriptor read(InputStream in) throws IOException {
        ModuleDescriptor d = JSON.readValue(in, ModuleDescriptor.class);
        d.validate();
        return d;
    }

    /** Vérifie les champs obligatoires et les formats ; lève {@link IllegalArgumentException}. */
    public void validate() {
        if (id == null || !id.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("id de module invalide : " + id);
        }
        if (main == null || main.isBlank()) {
            throw new IllegalArgumentException("classe principale (main) manquante");
        }
        semVer();
        apiRange();
        if (dependencies == null) {
            dependencies = new ArrayList<>();
        }
        for (Dependency dep : dependencies) {
            if (dep.id == null || dep.id.equals(id)) {
                throw new IllegalArgumentException("dépendance invalide : " + dep.id);
            }
            VersionRange.parse(dep.version);
        }
    }

    public SemVer semVer() {
        return SemVer.parse(version);
    }

    public VersionRange apiRange() {
        return VersionRange.parse(apiVersion == null ? "^" + KsmcApiVersion.CURRENT.major() + ".0" : apiVersion);
    }

    /** Vrai si le module accepte l'API du controller. */
    public boolean compatibleWith(SemVer api) {
        return apiRange().matches(api);
    }
}
