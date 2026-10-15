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

/** Configuration partagée entre plusieurs tasks (équivalent des GroupConfiguration de CloudNet). */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("ServiceGroup")
@Plural("servicegroups")
@Singular("servicegroup")
@ShortNames("mcgroup")
@AdditionalPrinterColumn(name = "Targets", jsonPath = ".spec.targetEnvironments")
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class ServiceGroup extends CustomResource<ServiceGroupSpec, KsmcStatus> implements Namespaced {
}
