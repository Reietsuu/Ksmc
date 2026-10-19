package io.ksmc.api.spi;

import io.fabric8.kubernetes.api.model.Pod;
import io.ksmc.api.model.CloudService;

/** Modifie le Pod d'un service avant sa création (labels, sidecars, tolérances...). Appelé dans l'ordre d'enregistrement. */
public interface ServicePodCustomizer {

    void customize(CloudService service, Pod pod);
}
