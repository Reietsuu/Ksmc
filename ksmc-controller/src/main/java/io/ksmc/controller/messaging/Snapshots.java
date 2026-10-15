package io.ksmc.controller.messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudService;
import io.ksmc.wire.messaging.CloudEvent;

/** Conversion des ressources en données d'événements (JSON du protocole). */
public final class Snapshots {
    private Snapshots() {
    }

    public static JsonElement json(Object value) {
        return JsonParser.parseString(Serialization.asJson(value));
    }

    /** Événement sur un service, avec son instantané (nom, spec, statut). */
    public static CloudEvent service(String type, CloudService svc) {
        CloudEvent e = CloudEvent.of(type, svc.getMetadata().getName());
        e.task = svc.getSpec().task;
        e.environment = svc.getSpec().environment == null ? null : svc.getSpec().environment.name().toLowerCase();
        e.data = json(java.util.Map.of(
                "name", svc.getMetadata().getName(),
                "spec", svc.getSpec(),
                "status", svc.getStatus() == null ? java.util.Map.of() : svc.getStatus()));
        return e;
    }

    public static CloudEvent resource(String type, HasMetadata resource, String task) {
        CloudEvent e = CloudEvent.of(type, resource.getMetadata().getName());
        e.task = task;
        e.data = json(resource);
        return e;
    }
}
