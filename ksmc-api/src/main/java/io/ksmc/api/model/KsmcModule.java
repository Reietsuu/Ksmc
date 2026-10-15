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

/** Module chargé par le controller pour étendre Ksmc. */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("KsmcModule")
@Plural("ksmcmodules")
@Singular("ksmcmodule")
@ShortNames("mcmod")
@AdditionalPrinterColumn(name = "Version", jsonPath = ".status.loadedVersion")
@AdditionalPrinterColumn(name = "Phase", jsonPath = ".status.phase")
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class KsmcModule extends CustomResource<KsmcModuleSpec, KsmcModuleStatus> implements Namespaced {
}
