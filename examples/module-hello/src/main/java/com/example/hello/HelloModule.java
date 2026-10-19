package com.example.hello;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.api.module.CliCommand;
import io.ksmc.api.module.DashboardPage;
import io.ksmc.api.module.KsmcModule;
import io.ksmc.api.module.ModuleContext;
import io.ksmc.api.module.RestRoutes.Response;
import io.ksmc.api.spi.ServicePodCustomizer;
import io.ksmc.wire.messaging.ChannelMessage;
import io.ksmc.wire.messaging.EventFilter;
import io.ksmc.wire.messaging.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Module d'exemple. Configuration ({@code KsmcModule.spec.config}) : {@code greeting} (défaut « Hello »).
 *
 * <ul>
 *   <li>{@code GET /api/v1/modules/hello/hello} (permission {@code hello.read}) : message de salutation ;</li>
 *   <li>{@code POST /api/v1/modules/hello/broadcast?text=...} ({@code hello.write}) : envoie {@code text} sur le
 *       channel {@code hello} à tous les serveurs (reçu par {@code examples/plugin-hello}) ;</li>
 *   <li>commande CLI {@code ksmc hello broadcast <text>}, page « Hello » du dashboard ;</li>
 *   <li>journalise les services démarrés et ajoute le label {@code example.ksmc.io/hello=true} à leurs Pods.</li>
 * </ul>
 */
public final class HelloModule implements KsmcModule {

    private volatile String greeting = "Hello";

    @Override
    public void onEnable(ModuleContext ctx) {
        onConfigChanged(ctx.config());

        ctx.rest().get("hello", "hello.read", req -> {
            Map<String, Object> body = new HashMap<>();
            body.put("message", greeting + ", " + req.user() + " !");
            body.put("services", ctx.kubernetes().resources(io.ksmc.api.model.CloudService.class)
                    .inNamespace(ctx.namespace()).list().getItems().size());
            return Response.ok(body);
        });
        ctx.rest().post("broadcast", "hello.write", req -> {
            String text = req.queryParam("text") != null ? req.queryParam("text") : greeting;
            ctx.messaging().channels().send(ChannelMessage.of("hello", text).target(Target.environment("server")));
            return Response.status(202, Map.of("sent", text));
        });
        ctx.cliCommand(new CliCommand("broadcast", "Envoie un message à tous les serveurs",
                List.of(new CliCommand.Argument("text", "message", true)), "POST", "broadcast"));
        ctx.dashboardPage(new DashboardPage("Hello", "hand", "web/hello.js"));

        ctx.registry().register(ServicePodCustomizer.class,
                (service, pod) -> pod.getMetadata().getLabels().put("example.ksmc.io/hello", "true"));

        try {
            ctx.events().subscribe(EventFilter.type("service.started"),
                    event -> ctx.logger().info("{} : service démarré {}", greeting, event.subject));
        } catch (RuntimeException e) {
            ctx.logger().warn("Événements indisponibles pour l'instant : {}", e.getMessage());
        }
        ctx.logger().info("Module hello activé ({})", greeting);
    }

    @Override
    public void onConfigChanged(JsonNode config) {
        greeting = config.path("greeting").asText("Hello");
    }
}
