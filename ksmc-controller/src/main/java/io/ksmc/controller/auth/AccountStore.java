package io.ksmc.controller.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.ksmc.controller.template.Digests;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Comptes, rôles et jetons d'API, stockés dans le Secret {@code ksmc-accounts} (JSON, sans base de données).
 * Écritures optimistes (resourceVersion) avec nouvel essai ; lectures servies par un cache court invalidé à chaque
 * écriture locale.
 */
public final class AccountStore {

    public static final String SECRET = "ksmc-accounts";
    static final String KEY = "accounts.json";
    static final Duration CACHE = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        public String passwordHash;
        public List<String> roles = new ArrayList<>();
        public boolean mustChangePassword;
        public String createdAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        public String name;
        public String user;
        /** SHA-256 hexadécimal du secret du jeton. */
        public String hash;
        public String createdAt;
        public String expiresAt;
        public String lastUsedAt;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        public Map<String, User> users = new LinkedHashMap<>();
        public Map<String, List<String>> roles = new LinkedHashMap<>();
        public Map<String, Token> tokens = new LinkedHashMap<>();
    }

    /** Utilisateur authentifié : nom, permissions effectives, changement de mot de passe exigé. */
    public record Account(String name, Set<String> permissions, boolean mustChangePassword) {
    }

    /** Jeton créé : {@code value} n'est connu qu'à ce moment. */
    public record CreatedToken(String id, String value) {
    }

    private record Snapshot(Data data, String resourceVersion, Instant readAt) {
    }

    private final KubernetesClient client;
    private final String namespace;
    private final Clock clock;
    private volatile Snapshot cache;

    public AccountStore(KubernetesClient client, String namespace, Clock clock) {
        this.client = client;
        this.namespace = namespace;
        this.clock = clock;
    }

    // ---- Lecture / écriture du Secret ------------------------------------------------------------

    private Snapshot load() {
        Snapshot c = cache;
        if (c != null && clock.instant().isBefore(c.readAt().plus(CACHE))) {
            return c;
        }
        Secret secret = client.secrets().inNamespace(namespace).withName(SECRET).get();
        Data data = new Data();
        String version = null;
        if (secret != null) {
            version = secret.getMetadata().getResourceVersion();
            String encoded = secret.getData() == null ? null : secret.getData().get(KEY);
            if (encoded != null) {
                try {
                    data = JSON.readValue(Base64.getDecoder().decode(encoded), Data.class);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Secret " + SECRET + " illisible : " + e.getMessage(), e);
                }
            }
        }
        c = new Snapshot(data, version, clock.instant());
        cache = c;
        return c;
    }

    /** Lecture seule des données (copie). */
    public Data snapshot() {
        return copy(load().data());
    }

    /** Applique une modification avec concurrence optimiste ; renvoie la valeur calculée par {@code change}. */
    private <T> T update(Function<Data, T> change) {
        for (int attempt = 0; ; attempt++) {
            cache = null;
            Snapshot s = load();
            Data data = copy(s.data());
            T result = change.apply(data);
            String json;
            try {
                json = JSON.writeValueAsString(data);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            Secret secret = new SecretBuilder().withNewMetadata().withName(SECRET).withNamespace(namespace)
                    .withResourceVersion(s.resourceVersion())
                    .addToLabels("app.kubernetes.io/managed-by", "ksmc").endMetadata()
                    .withType("Opaque")
                    .addToData(KEY, Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))
                    .build();
            try {
                if (s.resourceVersion() == null) {
                    client.secrets().inNamespace(namespace).resource(secret).create();
                } else {
                    client.secrets().inNamespace(namespace).resource(secret).update();
                }
                cache = null;
                return result;
            } catch (KubernetesClientException e) {
                if (e.getCode() != 409 || attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    private static Data copy(Data data) {
        try {
            return JSON.readValue(JSON.writeValueAsBytes(data), Data.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Initialisation ----------------------------------------------------------------------------

    /**
     * Crée les rôles par défaut manquants et, s'il n'existe pas, le compte {@code admin} avec le mot de passe initial
     * (changement exigé à la première connexion).
     */
    public void bootstrap(String initialAdminPassword) {
        Data current = load().data();
        boolean rolesMissing = Permissions.defaultRoles().keySet().stream().anyMatch(r -> !current.roles.containsKey(r));
        boolean adminMissing = initialAdminPassword != null && !initialAdminPassword.isBlank()
                && !current.users.containsKey("admin");
        if (!rolesMissing && !adminMissing) {
            return;
        }
        update(data -> {
            Permissions.defaultRoles().forEach(data.roles::putIfAbsent);
            if (adminMissing && !data.users.containsKey("admin")) {
                User admin = new User();
                admin.passwordHash = PasswordHasher.hash(initialAdminPassword);
                admin.roles = List.of("admin");
                admin.mustChangePassword = true;
                admin.createdAt = clock.instant().toString();
                data.users.put("admin", admin);
            }
            return null;
        });
    }

    // ---- Utilisateurs ------------------------------------------------------------------------------

    public static void validateName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("nom invalide (minuscules, chiffres, . _ -, 32 caractères max) : " + name);
        }
    }

    /** Vérifie le mot de passe ; le hachage est calculé même pour un compte inconnu (temps constant). */
    public Optional<Account> authenticate(String user, String password) {
        Data data = load().data();
        User u = data.users.get(user);
        boolean ok = PasswordHasher.verify(password, u == null ? DUMMY_HASH : u.passwordHash);
        return ok && u != null ? Optional.of(account(data, user, u)) : Optional.empty();
    }

    private static final String DUMMY_HASH = PasswordHasher.hash("ksmc-dummy-password-for-timing");

    public Optional<Account> account(String user) {
        Data data = load().data();
        User u = data.users.get(user);
        return u == null ? Optional.empty() : Optional.of(account(data, user, u));
    }

    private static Account account(Data data, String name, User u) {
        Set<String> permissions = new LinkedHashSet<>();
        for (String role : u.roles) {
            permissions.addAll(data.roles.getOrDefault(role, List.of()));
        }
        return new Account(name, Set.copyOf(permissions), u.mustChangePassword);
    }

    public void createUser(String name, String password, List<String> roles, boolean mustChangePassword) {
        validateName(name);
        PasswordHasher.requireStrong(password);
        String hash = PasswordHasher.hash(password);
        update(data -> {
            if (data.users.containsKey(name)) {
                throw new IllegalStateException("l'utilisateur existe déjà : " + name);
            }
            requireRoles(data, roles);
            User u = new User();
            u.passwordHash = hash;
            u.roles = new ArrayList<>(roles);
            u.mustChangePassword = mustChangePassword;
            u.createdAt = clock.instant().toString();
            data.users.put(name, u);
            return null;
        });
    }

    public void setPassword(String name, String password, boolean mustChangePassword) {
        PasswordHasher.requireStrong(password);
        String hash = PasswordHasher.hash(password);
        modifyUser(name, u -> {
            u.passwordHash = hash;
            u.mustChangePassword = mustChangePassword;
        });
    }

    public void setRoles(String name, List<String> roles) {
        update(data -> {
            requireRoles(data, roles);
            User u = data.users.get(name);
            if (u == null) {
                throw new java.util.NoSuchElementException("utilisateur inconnu : " + name);
            }
            u.roles = new ArrayList<>(roles);
            return null;
        });
    }

    public void deleteUser(String name) {
        update(data -> {
            if (data.users.remove(name) == null) {
                throw new java.util.NoSuchElementException("utilisateur inconnu : " + name);
            }
            data.tokens.values().removeIf(t -> name.equals(t.user));
            return null;
        });
    }

    private void modifyUser(String name, Consumer<User> change) {
        update(data -> {
            User u = data.users.get(name);
            if (u == null) {
                throw new java.util.NoSuchElementException("utilisateur inconnu : " + name);
            }
            change.accept(u);
            return null;
        });
    }

    private static void requireRoles(Data data, List<String> roles) {
        for (String r : roles) {
            if (!data.roles.containsKey(r)) {
                throw new IllegalArgumentException("rôle inconnu : " + r);
            }
        }
    }

    // ---- Rôles -------------------------------------------------------------------------------------

    public void putRole(String name, List<String> permissions) {
        validateName(name);
        update(data -> {
            data.roles.put(name, new ArrayList<>(permissions));
            return null;
        });
    }

    public void deleteRole(String name) {
        update(data -> {
            boolean used = data.users.values().stream().anyMatch(u -> u.roles.contains(name));
            if (used) {
                throw new IllegalStateException("rôle utilisé par des comptes : " + name);
            }
            if (data.roles.remove(name) == null) {
                throw new java.util.NoSuchElementException("rôle inconnu : " + name);
            }
            return null;
        });
    }

    // ---- Jetons d'API ------------------------------------------------------------------------------

    /** Crée un jeton {@code ksmc_<id>_<secret>} ; seule l'empreinte du secret est conservée. */
    public CreatedToken createToken(String user, String name, Instant expiresAt) {
        String id = random(12);
        String secret = random(40);
        update(data -> {
            if (!data.users.containsKey(user)) {
                throw new java.util.NoSuchElementException("utilisateur inconnu : " + user);
            }
            Token t = new Token();
            t.name = name;
            t.user = user;
            t.hash = Digests.sha256(secret);
            t.createdAt = clock.instant().toString();
            t.expiresAt = expiresAt == null ? null : expiresAt.toString();
            data.tokens.put(id, t);
            return null;
        });
        return new CreatedToken(id, "ksmc_" + id + "_" + secret);
    }

    /** Jetons (sans empreinte) ; {@code user == null} : tous. */
    public Map<String, Token> tokens(String user) {
        Map<String, Token> out = new LinkedHashMap<>();
        load().data().tokens.forEach((id, t) -> {
            if (user == null || user.equals(t.user)) {
                Token copy = new Token();
                copy.name = t.name;
                copy.user = t.user;
                copy.createdAt = t.createdAt;
                copy.expiresAt = t.expiresAt;
                out.put(id, copy);
            }
        });
        return out;
    }

    public Optional<Token> token(String id) {
        return Optional.ofNullable(load().data().tokens.get(id));
    }

    public void revokeToken(String id) {
        update(data -> {
            if (data.tokens.remove(id) == null) {
                throw new java.util.NoSuchElementException("jeton inconnu : " + id);
            }
            return null;
        });
    }

    /** Compte associé à un jeton valide (non révoqué, non expiré). */
    public Optional<Account> authenticateToken(String value) {
        if (value == null || !value.startsWith("ksmc_")) {
            return Optional.empty();
        }
        String[] parts = value.split("_", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        Data data = load().data();
        Token t = data.tokens.get(parts[1]);
        if (t == null || t.hash == null) {
            return Optional.empty();
        }
        byte[] expected = t.hash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = Digests.sha256(parts[2]).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        if (t.expiresAt != null && !clock.instant().isBefore(Instant.parse(t.expiresAt))) {
            return Optional.empty();
        }
        User u = data.users.get(t.user);
        // Un jeton ne contourne pas le changement de mot de passe obligatoire.
        return u == null ? Optional.empty() : Optional.of(account(data, t.user, u));
    }

    static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
