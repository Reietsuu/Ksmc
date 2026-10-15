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

/** Décrit comment obtenir et lancer un logiciel serveur ou proxy. */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("SoftwareDefinition")
@Plural("softwaredefinitions")
@Singular("softwaredefinition")
@ShortNames("mcsw")
@AdditionalPrinterColumn(name = "Env", jsonPath = ".spec.environment")
@AdditionalPrinterColumn(name = "Resolver", jsonPath = ".spec.resolver.type")
@AdditionalPrinterColumn(name = "Latest", jsonPath = ".status.latest")
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class SoftwareDefinition extends CustomResource<SoftwareDefinitionSpec, SoftwareDefinitionStatus> implements Namespaced {
}
