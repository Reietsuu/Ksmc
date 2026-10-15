package io.ksmc.api.model;

import io.fabric8.kubernetes.client.CustomResource;
import java.util.List;

/** Constantes partagées (groupe d'API, labels, annotations). */
public final class Ksmc {
    public static final String GROUP = "ksmc.io";
    public static final String VERSION = "v1alpha1";
    public static final String API_VERSION = GROUP + "/" + VERSION;

    public static final String LABEL_TASK = GROUP + "/task";
    public static final String LABEL_SERVICE = GROUP + "/service";
    public static final String LABEL_ENVIRONMENT = GROUP + "/environment";
    public static final String LABEL_PREPARED = GROUP + "/prepared";
    public static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY = "ksmc";

    public static final String ANNOTATION_CONFIG_HASH = GROUP + "/config-hash";
    public static final String ANNOTATION_START_REQUESTS = GROUP + "/start-requests";

    /** Tous les types de ressources Ksmc. */
    public static final List<Class<? extends CustomResource<?, ?>>> RESOURCE_TYPES = List.of(
            ServiceTask.class, ServiceGroup.class, ServiceTemplate.class,
            CloudService.class, SoftwareDefinition.class, KsmcModule.class);

    private Ksmc() {
    }
}
