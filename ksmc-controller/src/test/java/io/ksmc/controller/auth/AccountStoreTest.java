package io.ksmc.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class AccountStoreTest {
    KubernetesClient client;
    MutableClock clock;
    AccountStore store;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");

        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new AccountStore(client, "test", clock);
        store.bootstrap("initial-password-123");
    }

    @Test
    void bootstrapCreatesAdminWithForcedPasswordChangeAndDefaultRoles() {
        AccountStore.Account admin = store.authenticate("admin", "initial-password-123").orElseThrow();
        assertThat(admin.mustChangePassword()).isTrue();
        assertThat(admin.permissions()).containsExactly("*");
        assertThat(store.snapshot().roles).containsOnlyKeys("admin", "operator", "viewer");

        // Stocké haché (argon2id), jamais en clair.
        Secret secret = client.secrets().inNamespace("test").withName(AccountStore.SECRET).get();
        String json = new String(Base64.getDecoder().decode(secret.getData().get("accounts.json")), StandardCharsets.UTF_8);
        assertThat(json).contains("$argon2id$").doesNotContain("initial-password-123");

        // Un second démarrage ne réinitialise pas le compte.
        store.setPassword("admin", "nouveau-mot-de-passe", false);
        new AccountStore(client, "test", clock).bootstrap("autre-mot-de-passe-123");
        assertThat(store.authenticate("admin", "nouveau-mot-de-passe")).hasValueSatisfying(a ->
                assertThat(a.mustChangePassword()).isFalse());
    }

    @Test
    void wrongPasswordOrUnknownUserFails() {
        assertThat(store.authenticate("admin", "mauvais-mot-de-passe")).isEmpty();
        assertThat(store.authenticate("inconnu", "initial-password-123")).isEmpty();
    }

    @Test
    void viewerHasReadPermissionsOnly() {
        store.createUser("alice", "alice-password-1", List.of("viewer"), false);
        AccountStore.Account alice = store.authenticate("alice", "alice-password-1").orElseThrow();

        assertThat(Permissions.grants(alice.permissions(), Permissions.TASK_READ)).isTrue();
        assertThat(Permissions.grants(alice.permissions(), Permissions.SERVICE_WRITE)).isFalse();
        assertThat(Permissions.grants(alice.permissions(), "hello.read")).isFalse();

        store.putRole("hello-users", List.of("hello.*", Permissions.TASK_READ));
        store.setRoles("alice", List.of("hello-users"));
        AccountStore.Account updated = store.account("alice").orElseThrow();
        assertThat(Permissions.grants(updated.permissions(), "hello.write")).isTrue();
    }

    @Test
    void operatorCanManageServicesButNotAccounts() {
        store.createUser("bob", "bob-password-12", List.of("operator"), false);
        AccountStore.Account bob = store.account("bob").orElseThrow();
        assertThat(Permissions.grants(bob.permissions(), Permissions.SERVICE_COMMAND)).isTrue();
        assertThat(Permissions.grants(bob.permissions(), Permissions.TASK_START)).isTrue();
        assertThat(Permissions.grants(bob.permissions(), Permissions.ACCOUNT_ADMIN)).isFalse();
        assertThat(Permissions.grants(bob.permissions(), Permissions.MODULE_WRITE)).isFalse();
    }

    @Test
    void tokensAreShownOnceExpireAndCanBeRevoked() {
        AccountStore.CreatedToken t = store.createToken("admin", "ci", clock.now.plusSeconds(3600));
        assertThat(t.value()).startsWith("ksmc_" + t.id() + "_");
        assertThat(store.tokens("admin").get(t.id()).hash).isNull();
        assertThat(store.authenticateToken(t.value())).hasValueSatisfying(a -> assertThat(a.name()).isEqualTo("admin"));
        assertThat(store.authenticateToken(t.value() + "x")).isEmpty();
        assertThat(store.authenticateToken("ksmc_inconnu_secret")).isEmpty();

        clock.now = clock.now.plusSeconds(3600);
        assertThat(store.authenticateToken(t.value())).as("expiré").isEmpty();

        AccountStore.CreatedToken t2 = store.createToken("admin", "cli", null);
        store.revokeToken(t2.id());
        assertThat(store.authenticateToken(t2.value())).as("révoqué").isEmpty();
    }

    @Test
    void deletingAUserRevokesItsTokens() {
        store.createUser("carol", "carol-password", List.of("viewer"), false);
        AccountStore.CreatedToken t = store.createToken("carol", "script", null);
        store.deleteUser("carol");
        assertThat(store.authenticateToken(t.value())).isEmpty();
        assertThat(store.tokens(null)).isEmpty();
    }

    @Test
    void validationErrors() {
        assertThatThrownBy(() -> store.createUser("Bad Name", "long-enough-pw", List.of("viewer"), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createUser("dave", "court", List.of("viewer"), false))
                .hasMessageContaining("au moins");
        assertThatThrownBy(() -> store.createUser("dave", "long-enough-pw", List.of("absent"), false))
                .hasMessageContaining("rôle inconnu");
        assertThatThrownBy(() -> store.createUser("admin", "long-enough-pw", List.of("viewer"), false))
                .hasMessageContaining("existe déjà");
        assertThatThrownBy(() -> store.deleteRole("admin")).hasMessageContaining("utilisé");
    }
}
