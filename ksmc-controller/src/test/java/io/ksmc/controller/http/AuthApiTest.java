package io.ksmc.controller.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.controller.auth.AccountStore;
import io.ksmc.controller.auth.Jwt;
import io.ksmc.controller.auth.LoginRateLimiter;
import io.ksmc.controller.auth.Permissions;
import io.ksmc.controller.health.HealthRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class AuthApiTest {
    KubernetesClient client;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String INITIAL = "initial-password-123";
    MutableClock clock;
    AccountStore accounts;
    AuditLog audit;
    HttpServer server;
    final HttpClient http = HttpClient.newHttpClient();

    static final class MutableClock extends Clock {
        Instant now = Instant.now();

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
        accounts = new AccountStore(client, "test", clock);
        accounts.bootstrap(INITIAL);
        Jwt jwt = new Jwt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), clock);
        SessionAuthenticator sessions = new SessionAuthenticator(accounts, jwt);
        audit = new AuditLog(null, "test", clock, List::of);
        AuthApi auth = new AuthApi(accounts, jwt, new LoginRateLimiter(10, Duration.ofMinutes(1), clock),
                new LoginRateLimiter(50, Duration.ofMinutes(1), clock), new ExtensionRegistry(), audit);
        HttpRoutes protectedRoute = routes -> routes.post("/api/v1/test/tasks", ctx -> {
            ApiSupport.require(ctx, Permissions.TASK_WRITE);
            ctx.status(201).json(Map.of("ok", true));
        });
        server = new HttpServer(new HealthRegistry(), List.of(new ApiSupport(sessions), auth, protectedRoute)).start(0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    String login(String user, String password) throws Exception {
        HttpResponse<String> r = call("POST", "/api/v1/auth/login", null, Map.of("username", user, "password", password));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("accessToken").asText();
    }

    String adminWithChangedPassword() throws Exception {
        String first = login("admin", INITIAL);
        HttpResponse<String> r = call("POST", "/api/v1/auth/password", first,
                Map.of("currentPassword", INITIAL, "newPassword", "admin-password-2"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("accessToken").asText();
    }

    @Test
    void initialAdminMustChangePasswordBeforeAnythingElse() throws Exception {
        HttpResponse<String> r = call("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", INITIAL));
        assertThat(json(r).get("mustChangePassword").asBoolean()).isTrue();
        String token = json(r).get("accessToken").asText();

        assertThat(call("GET", "/api/v1/auth/me", token, null).statusCode()).isEqualTo(200);
        HttpResponse<String> blocked = call("POST", "/api/v1/test/tasks", token, Map.of());
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).get("code").asText()).isEqualTo("PasswordChangeRequired");

        HttpResponse<String> weak = call("POST", "/api/v1/auth/password", token,
                Map.of("currentPassword", INITIAL, "newPassword", "court"));
        assertThat(weak.statusCode()).isEqualTo(400);

        String fresh = adminWithChangedPassword();
        assertThat(call("POST", "/api/v1/test/tasks", fresh, Map.of()).statusCode()).isEqualTo(201);
        assertThat(audit.query("admin", "auth.password", null, 10)).hasSize(1);
    }

    @Test
    void unauthenticatedRequestsGetUniform401() throws Exception {
        HttpResponse<String> r = call("POST", "/api/v1/test/tasks", null, Map.of());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(json(r).get("code").asText()).isEqualTo("Unauthorized");
        assertThat(json(r).has("details")).isTrue();
        assertThat(call("POST", "/api/v1/test/tasks", "pas-un-jeton", Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/healthz", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void viewerIsForbiddenToWriteWithTheMissingPermission() throws Exception {
        accounts.createUser("alice", "alice-password-1", List.of("viewer"), false);
        HttpResponse<String> r = call("POST", "/api/v1/test/tasks", login("alice", "alice-password-1"), Map.of());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).get("details").get("permission").asText()).isEqualTo(Permissions.TASK_WRITE);
    }

    @Test
    void bruteForceIsRateLimitedForAtLeastAMinute() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", "faux-" + i))
                    .statusCode()).isEqualTo(401);
        }
        HttpResponse<String> limited = call("POST", "/api/v1/auth/login", null,
                Map.of("username", "admin", "password", INITIAL));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();

        clock.now = clock.now.plusSeconds(59);
        assertThat(call("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", INITIAL))
                .statusCode()).isEqualTo(429);
        clock.now = clock.now.plusSeconds(2);
        assertThat(call("POST", "/api/v1/auth/login", null, Map.of("username", "admin", "password", INITIAL))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void apiTokensWorkUntilRevoked() throws Exception {
        String admin = adminWithChangedPassword();
        HttpResponse<String> created = call("POST", "/api/v1/auth/tokens", admin, Map.of("name", "ci", "expiresInDays", 30));
        assertThat(created.statusCode()).isEqualTo(201);
        String token = json(created).get("token").asText();
        String id = json(created).get("id").asText();

        assertThat(call("POST", "/api/v1/test/tasks", token, Map.of()).statusCode()).isEqualTo(201);
        assertThat(call("GET", "/api/v1/auth/tokens", admin, null).body()).contains(id).doesNotContain(token);

        assertThat(call("DELETE", "/api/v1/auth/tokens/" + id, admin, null).statusCode()).isEqualTo(204);
        assertThat(call("POST", "/api/v1/test/tasks", token, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void sessionsExpireAndRefreshTokensDieWithThePassword() throws Exception {
        String first = login("admin", INITIAL);
        HttpResponse<String> changed = call("POST", "/api/v1/auth/password", first,
                Map.of("currentPassword", INITIAL, "newPassword", "admin-password-2"));
        String refresh = json(changed).get("refreshToken").asText();

        HttpResponse<String> renewed = call("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", refresh));
        assertThat(renewed.statusCode()).isEqualTo(200);
        String access = json(renewed).get("accessToken").asText();

        // Jeton altéré, puis expiré.
        assertThat(call("GET", "/api/v1/auth/me", access.substring(0, access.length() - 2) + "xx", null).statusCode())
                .isEqualTo(401);
        clock.now = clock.now.plus(AuthApi.ACCESS_TTL).plusSeconds(1);
        assertThat(call("GET", "/api/v1/auth/me", access, null).statusCode()).isEqualTo(401);

        // Mot de passe changé : l'ancien jeton de renouvellement est refusé.
        String current = login("admin", "admin-password-2");
        call("POST", "/api/v1/auth/password", current,
                Map.of("currentPassword", "admin-password-2", "newPassword", "admin-password-3"));
        assertThat(call("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", refresh)).statusCode())
                .isEqualTo(401);
        // Un jeton d'accès ne sert pas de jeton de renouvellement.
        assertThat(call("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", current)).statusCode())
                .isEqualTo(401);
    }

    @Test
    void adminManagesAccountsAndRoles() throws Exception {
        String admin = adminWithChangedPassword();
        assertThat(call("POST", "/api/v1/accounts", admin,
                Map.of("name", "bob", "password", "bob-password-12", "roles", List.of("operator"))).statusCode())
                .isEqualTo(201);
        assertThat(call("POST", "/api/v1/accounts", admin,
                Map.of("name", "bob", "password", "bob-password-12", "roles", List.of("operator"))).statusCode())
                .isEqualTo(409);
        String bob = login("bob", "bob-password-12");
        // Compte créé par un admin : changement de mot de passe exigé par défaut.
        assertThat(call("GET", "/api/v1/accounts", bob, null).statusCode()).isEqualTo(403);

        assertThat(call("PUT", "/api/v1/roles/support", admin, Map.of("permissions", List.of("ksmc.service.*")))
                .statusCode()).isEqualTo(204);
        assertThat(call("PUT", "/api/v1/accounts/bob/roles", admin, Map.of("roles", List.of("support"))).statusCode())
                .isEqualTo(204);
        assertThat(call("PUT", "/api/v1/accounts/bob/roles", admin, Map.of("roles", List.of("absent"))).statusCode())
                .isEqualTo(400);
        assertThat(json(call("GET", "/api/v1/roles", admin, null)).has("support")).isTrue();
        assertThat(call("DELETE", "/api/v1/accounts/admin", admin, null).statusCode()).isEqualTo(400);
        assertThat(call("DELETE", "/api/v1/accounts/bob", admin, null).statusCode()).isEqualTo(204);
        assertThat(json(call("GET", "/api/v1/permissions", admin, null)).get("builtin").toString())
                .contains(Permissions.SERVICE_COMMAND);
    }
}
