package io.ksmc.api.model;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** Famille de services gérés ensemble (équivalent de la ServiceTask de CloudNet). */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("ServiceTask")
@Plural("servicetasks")
@Singular("servicetask")
@ShortNames("mctask")
@AdditionalPrinterColumn(name = "Env", jsonPath = ".spec.environment")
@AdditionalPrinterColumn(name = "Software", jsonPath = ".spec.software.name")
@AdditionalPrinterColumn(name = "Version", jsonPath = ".spec.software.version")
@AdditionalPrinterColumn(name = "Min", jsonPath = ".spec.minServiceCount", type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "Ready", jsonPath = ".status.ready")
@AdditionalPrinterColumn(name = "Players", jsonPath = ".status.onlinePlayers", type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class ServiceTask extends CustomResource<ServiceTaskSpec, ServiceTaskStatus> implements Namespaced {
}
