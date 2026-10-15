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

/** Une instance de serveur ou de proxy (équivalent du ServiceInfoSnapshot de CloudNet). */
@Group(Ksmc.GROUP)
@Version(Ksmc.VERSION)
@Kind("CloudService")
@Plural("cloudservices")
@Singular("cloudservice")
@ShortNames("mcsvc")
@AdditionalPrinterColumn(name = "Task", jsonPath = ".spec.task")
@AdditionalPrinterColumn(name = "Phase", jsonPath = ".status.phase")
@AdditionalPrinterColumn(name = "Players", jsonPath = ".status.players")
@AdditionalPrinterColumn(name = "State", jsonPath = ".status.state", priority = 1)
@AdditionalPrinterColumn(name = "Address", jsonPath = ".status.address", priority = 1)
@AdditionalPrinterColumn(name = "Age", jsonPath = ".metadata.creationTimestamp", type = AdditionalPrinterColumn.Type.DATE)
public class CloudService extends CustomResource<CloudServiceSpec, CloudServiceStatus> implements Namespaced {
}
