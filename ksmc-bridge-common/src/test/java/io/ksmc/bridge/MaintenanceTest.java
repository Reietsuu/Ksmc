package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.wire.bridge.ServiceInfo;
import io.ksmc.wire.manifest.ServiceManifest;
import org.junit.jupiter.api.Test;

/** La maintenance d'un proxy suit le registre (changée à chaud par le controller), pas seulement le démarrage. */
class MaintenanceTest {

    @Test
    void maintenanceFollowsTheRegistryWithoutRestart() {
        ServiceManifest m = new ServiceManifest();
        m.properties.put("ksmc.maintenance.whitelist", "Steve");
        KsmcBridge bridge = new KsmcBridge(new BridgeEnv("prod", "proxy-1", "proxy", "proxy", null, null, null, m),
                new FakePlatform());
        Router.Permissions none = p -> false;
        assertThat(bridge.allowedToJoin("Alex", none)).as("pas de maintenance au démarrage").isTrue();

        ServiceInfo self = new ServiceInfo();
        self.name = "proxy-1";
        self.properties.put("ksmc.maintenance", "true");
        self.properties.put("ksmc.maintenance.whitelist", "Steve");
        bridge.registry().update(self);
        assertThat(bridge.allowedToJoin("Alex", none)).isFalse();
        assertThat(bridge.allowedToJoin("Steve", none)).isTrue();
        assertThat(bridge.allowedToJoin("Alex", p -> p.equals("ksmc.maintenance.bypass"))).isTrue();

        self.properties.remove("ksmc.maintenance");
        bridge.registry().update(self);
        assertThat(bridge.allowedToJoin("Alex", none)).isTrue();
    }
}
