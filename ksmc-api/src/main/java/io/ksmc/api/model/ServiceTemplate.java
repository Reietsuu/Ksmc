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

/** Source de fichiers appliquée aux services. */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("ServiceTemplate")
@Plural("servicetemplates")
@Singular("servicetemplate")
@ShortNames("mctpl")
@AdditionalPrinterColumn(name = "Digest", jsonPath = ".status.digest", priority = 1)
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class ServiceTemplate extends CustomResource<ServiceTemplateSpec, ServiceTemplateStatus> implements Namespaced {
}
