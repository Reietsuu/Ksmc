package io.ksmc.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contextes de connexion ({@code ~/.ksmc/config.yaml}, ou {@code $KSMC_CONFIG}) : URL du controller et jetons.
 * Le fichier contient des secrets : il est créé lisible par l'utilisateur seul (600).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Config {

    public static final YAMLFactory YAML_FACTORY = new YAMLFactory();
    public static final ObjectMapper YAML = new ObjectMapper(YAML_FACTORY);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Context {
        public String server;
        public String user;
        /** Jeton d'accès de session ou jeton d'API {@code ksmc_...}. */
        public String token;
        /** Jeton de renouvellement (sessions uniquement). */
        public String refreshToken;
    }

    public String current;
    public Map<String, Context> contexts = new LinkedHashMap<>();

    static Path path() {
        String env = System.getProperty("ksmc.config", System.getenv("KSMC_CONFIG"));
        return env != null && !env.isBlank() ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".ksmc", "config.yaml");
    }

    public static Config load() throws IOException {
        Path p = path();
        if (!Files.isRegularFile(p)) {
            return new Config();
        }
        Config c = YAML.readValue(p.toFile(), Config.class);
        if (c.contexts == null) {
            c.contexts = new LinkedHashMap<>();
        }
        return c;
    }

    public void save() throws IOException {
        Path p = path();
        Files.createDirectories(p.toAbsolutePath().getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        YAML.writeValue(tmp.toFile(), this);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (posix) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** Contexte demandé ({@code --context}) ou courant ; lève {@link CliException} s'il n'existe pas. */
    public Context context(String name) {
        String n = name != null ? name : current;
        if (n == null) {
            throw new CliException(1, "aucun contexte : connectez-vous avec « ksmc login <url> »");
        }
        Context c = contexts.get(n);
        if (c == null) {
            throw new CliException(1, "contexte inconnu : " + n);
        }
        return c;
    }

    public String contextName(String name) {
        return name != null ? name : current;
    }
}
