package io.ksmc.controller.bridge;

import io.ksmc.api.model.CloudService;
import io.ksmc.api.model.CloudServiceStatus;
import io.ksmc.api.model.Environment;
import io.ksmc.api.model.Ksmc;
import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.bridge.ServiceReport;
import java.util.Map;

/** Construction des {@link ServiceInfo} publiées aux bridges. */
public final class ServiceInfos {
    private ServiceInfos() {
    }

    public static ServiceInfo of(CloudService svc, ServiceReport report) {
        ServiceInfo i = new ServiceInfo();
        i.name = svc.getMetadata().getName();
        i.task = svc.getSpec().task;
        i.environment = svc.getSpec().environment == Environment.PROXY ? "proxy" : "server";
        Map<String, String> props = svc.getSpec().properties;
        i.properties.putAll(props);
        i.fallback = "true".equals(props.get("ksmc.fallback"));
        i.fallbackPriority = parse(props.get("ksmc.fallback.priority"));
        i.fallbackPermission = props.get("ksmc.fallback.permission");
        i.maintenance = "true".equals(props.get("ksmc.maintenance"));
        Map<String, String> labels = svc.getMetadata().getLabels();
        i.prepared = labels != null && "true".equals(labels.get(Ksmc.LABEL_PREPARED));
        CloudServiceStatus st = svc.getStatus();
        if (st != null) {
            i.phase = st.phase == null ? null : st.phase.name();
            if (st.address != null && st.address.contains(":")) {
                i.host = st.address.substring(0, st.address.lastIndexOf(':'));
                i.port = parse(st.address.substring(st.address.lastIndexOf(':') + 1));
            }
            i.onlinePlayers = st.onlinePlayers == null ? 0 : st.onlinePlayers;
            i.maxPlayers = st.maxPlayers == null ? 0 : st.maxPlayers;
            i.motd = st.motd;
            i.state = st.state;
            i.tps = st.tps;
            i.properties.putAll(st.properties);
        }
        if (report != null) {
            i.onlinePlayers = report.onlinePlayers;
            i.maxPlayers = report.maxPlayers;
            i.motd = report.motd;
            i.state = report.state;
            i.tps = report.tps;
            i.properties.putAll(report.properties);
        }
        i.updatedAt = System.currentTimeMillis();
        return i;
    }

    private static int parse(String v) {
        try {
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
