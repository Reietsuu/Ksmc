package io.ksmc.controller.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.module.CliCommand;
import io.ksmc.api.module.DashboardPage;
import io.ksmc.api.module.ExtensionRegistry;
import io.ksmc.api.module.Registry;
import io.ksmc.api.module.RestRoutes;
import io.ksmc.controller.health.HealthRegistry;
import io.ksmc.controller.module.ModuleExtensions;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleApiTest {

    static final String TOKEN = "secret-token";

    @TempDir
    Path tmp;
    ExtensionRegistry extensions;
    HttpServer server;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry();
        // Jeton admin, ou « reader » qui n'a que hello.read.
        ApiAuthenticator auth = ctx -> {
            String h = ctx.header("Authorization");
            if (("Bearer " + TOKEN).equals(h)) {
                return Optional.of(new ApiAuthenticator.Principal("admin", Set.of("*")));
            }
            return "Bearer reader".equals(h)
                    ? Optional.of(new ApiAuthenticator.Principal("reader", Set.of("hello.read"))) : Optional.empty();
        };
        server = new HttpServer(new HealthRegistry(), List.of(new ModuleApi(extensions, auth))).start(0);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    Registry hello() {
        Registry r = extensions.scope("hello");
        ClassLoader cl = getClass().getClassLoader();
        r.register(ModuleExtensions.Route.class, new ModuleExtensions.Route("hello", RestRoutes.Method.GET, "hello",
                "hello.read", req -> RestRoutes.Response.ok(Map.of("message", "Bonjour " + req.user())), cl));
        r.register(ModuleExtensions.Route.class, new ModuleExtensions.Route("hello", RestRoutes.Method.POST,
                "greet/{who}", "hello.write", req -> RestRoutes.Response.status(201,
                        req.pathParam("who") + ":" + req.queryParam("lang") + ":" + req.body()), cl));
        r.register(ModuleExtensions.Route.class, new ModuleExtensions.Route("hello", RestRoutes.Method.GET, "boom", null,
                req -> {
                    throw new IllegalStateException("panne");
                }, cl));
        return r;
    }

    @Test
    void routesAreServedUnderTheModulePrefixWithPermissions() throws Exception {
        hello();

        HttpResponse<String> ok = call("GET", "/api/v1/modules/hello/hello", "reader", null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("Bonjour reader");

        assertThat(call("GET", "/api/v1/modules/hello/hello", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/modules/hello/hello", "wrong", null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/modules/hello/greet/Steve", "reader", "x").statusCode()).isEqualTo(403);

        HttpResponse<String> created = call("POST", "/api/v1/modules/hello/greet/Steve?lang=fr", TOKEN, "corps");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).isEqualTo("Steve:fr:corps");
    }

    @Test
    void moduleErrorsBecome500AndUnknownRoutes404() throws Exception {
        hello();
        HttpResponse<String> boom = call("GET", "/api/v1/modules/hello/boom", TOKEN, null);
        assertThat(boom.statusCode()).isEqualTo(500);
        assertThat(boom.body()).contains("IllegalStateException").doesNotContain("panne");

        assertThat(call("GET", "/api/v1/modules/hello/nope", TOKEN, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/modules/other/hello", TOKEN, null).statusCode()).isEqualTo(404);
    }

    @Test
    void routesDisappearWhenTheModuleIsDisabled() throws Exception {
        hello();
        assertThat(call("GET", "/api/v1/modules/hello/hello", TOKEN, null).statusCode()).isEqualTo(200);

        extensions.closeScope("hello");

        assertThat(call("GET", "/api/v1/modules/hello/hello", TOKEN, null).statusCode()).isEqualTo(404);
    }

    @Test
    void cliDescriptorsAndDashboardPagesArePublished() throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res/web"));
        Files.writeString(res.resolve("hello.js"), "export function mount(el) {}");
        ClassLoader bundleLoader = new URLClassLoader(new java.net.URL[] {tmp.resolve("res").toUri().toURL()}, null);
        Registry r = extensions.scope("hello");
        r.register(ModuleExtensions.Cli.class, new ModuleExtensions.Cli("hello", new CliCommand("greet", "Saluer",
                List.of(new CliCommand.Argument("who", "nom", true)), "POST", "greet/{who}")));
        r.register(ModuleExtensions.Page.class, new ModuleExtensions.Page("hello",
                new DashboardPage("Hello", "hand", "web/hello.js"), bundleLoader));

        assertThat(call("GET", "/api/v1/modules/hello/cli", TOKEN, null).body())
                .contains("\"name\":\"greet\"", "\"path\":\"greet/{who}\"");
        assertThat(call("GET", "/api/v1/modules/hello/cli", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/cli/commands", TOKEN, null).body()).contains("\"hello\":[{\"name\":\"greet\"");
        assertThat(call("GET", "/api/v1/ui/modules", TOKEN, null).body())
                .contains("\"bundle\":\"/modules/hello/ui.js\"", "\"title\":\"Hello\"");
        HttpResponse<String> js = call("GET", "/modules/hello/ui.js", null, null);
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("javascript"));
        assertThat(js.body()).contains("mount");
        assertThat(call("GET", "/modules/absent/ui.js", null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void pathMatchingAndWildcardPermissions() {
        assertThat(ModuleApi.match("a/{x}/b", "a/1/b")).containsEntry("x", "1");
        assertThat(ModuleApi.match("a/{x}", "a/1/b")).isNull();
        assertThat(ModuleApi.match("a/{x}", "a/")).isNull();
        ApiAuthenticator.Principal p = new ApiAuthenticator.Principal("u", Set.of("hello.*"));
        assertThat(p.has("hello.read")).isTrue();
        assertThat(p.has("hello.admin.write")).isTrue();
        assertThat(p.has("other.read")).isFalse();
        assertThat(p.has(null)).isTrue();
    }

}
