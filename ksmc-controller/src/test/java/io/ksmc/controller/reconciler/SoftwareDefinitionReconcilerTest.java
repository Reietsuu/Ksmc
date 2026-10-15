package io.ksmc.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.ksmc.api.model.SoftwareDefinition;
import io.ksmc.api.spi.ResolverContext;
import io.ksmc.controller.software.SoftwareCatalog;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SoftwareDefinitionReconcilerTest {
    static final String API = "https://fill.papermc.io/v3/projects/paper";

    final Map<String, String> responses = new HashMap<>();
    final ResolverContext ctx = url -> {
        String body = responses.get(url);
        if (body == null) {
            throw new ResolverContext.HttpStatusException(404, url);
        }
        return body;
    };

    static SoftwareDefinition paper() {
        return Serialization.unmarshal("""
                apiVersion: ksmc.io/v1alpha1
                kind: SoftwareDefinition
                metadata: {name: paper, uid: u1, resourceVersion: "1", generation: 3}
                spec:
                  environment: server
                  family: paper
                  resolver: {type: papermc, params: {project: paper}}
                """, SoftwareDefinition.class);
    }

    @Test
    void publishesVersionsAndLatest() {
        responses.put(API, "{\"versions\":{\"1.21\":[\"1.21.4\",\"1.21.3\"]}}");
        responses.put(API + "/versions/1.21.4/builds/latest", """
                {"id":232,"channel":"STABLE","downloads":{"server:default":{"url":"https://x/p.jar","checksums":{"sha256":"a"}}}}""");
        var reconciler = new SoftwareDefinitionReconciler(new SoftwareCatalog(ctx, Clock.systemUTC()));

        UpdateControl<SoftwareDefinition> result = reconciler.reconcile(paper(), null);

        var status = result.getResource().orElseThrow().getStatus();
        assertThat(status.versions).containsExactly("1.21.4", "1.21.3");
        assertThat(status.latest).isEqualTo("1.21.4@232");
        assertThat(status.observedGeneration).isEqualTo(3);
        assertThat(Conditions.isTrue(status, Conditions.READY)).isTrue();
        assertThat(result.getScheduleDelay()).contains(SoftwareDefinitionReconciler.REFRESH.toMillis());
    }

    @Test
    void reportsResolverErrorsAndRetriesSooner() {
        var reconciler = new SoftwareDefinitionReconciler(new SoftwareCatalog(ctx, Clock.systemUTC()));

        UpdateControl<SoftwareDefinition> result = reconciler.reconcile(paper(), null);

        var status = result.getResource().orElseThrow().getStatus();
        assertThat(Conditions.isTrue(status, Conditions.READY)).isFalse();
        assertThat(status.conditions.getFirst().getReason()).isEqualTo("ResolverError");
        assertThat(result.getScheduleDelay()).contains(SoftwareDefinitionReconciler.RETRY.toMillis());
    }
}
