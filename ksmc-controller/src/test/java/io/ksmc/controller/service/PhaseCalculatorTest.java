package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.ksmc.api.model.CloudServiceStatus.Phase;
import org.junit.jupiter.api.Test;

class PhaseCalculatorTest {

    static Pod pod(String statusYaml) {
        return Serialization.unmarshal("""
                apiVersion: v1
                kind: Pod
                metadata: {name: lobby-1}
                status:
                %s
                """.formatted(statusYaml.indent(2)), Pod.class);
    }

    @Test
    void noPodIsPending() {
        assertThat(PhaseCalculator.compute(null).phase()).isEqualTo(Phase.Pending);
    }

    @Test
    void unschedulableKeepsSchedulerMessage() {
        var r = PhaseCalculator.compute(pod("""
                phase: Pending
                conditions:
                - {type: PodScheduled, status: "False", reason: Unschedulable, message: "0/1 nodes: Insufficient memory"}
                """));
        assertThat(r.phase()).isEqualTo(Phase.Pending);
        assertThat(r.reason()).isEqualTo("Unschedulable");
        assertThat(r.message()).contains("Insufficient memory");
    }

    @Test
    void initRunningIsPreparing() {
        var r = PhaseCalculator.compute(pod("""
                phase: Pending
                initContainerStatuses:
                - {name: prepare, state: {running: {}}}
                containerStatuses:
                - {name: server, state: {waiting: {reason: PodInitializing}}}
                """));
        assertThat(r.phase()).isEqualTo(Phase.Preparing);
    }

    @Test
    void initFailureUsesWrapperReason() {
        var r = PhaseCalculator.compute(pod("""
                phase: Failed
                initContainerStatuses:
                - {name: prepare, state: {terminated: {exitCode: 1, message: "TemplateUnavailable: lobby: HTTP 404"}}}
                """));
        assertThat(r.phase()).isEqualTo(Phase.Failed);
        assertThat(r.reason()).isEqualTo("TemplateUnavailable");
        assertThat(r.message()).isEqualTo("lobby: HTTP 404");
    }

    @Test
    void runningNotReadyIsStartingThenRunning() {
        String base = """
                phase: Running
                initContainerStatuses:
                - {name: prepare, state: {terminated: {exitCode: 0}}}
                containerStatuses:
                - {name: server, ready: %s, state: {running: {startedAt: "2026-10-15T10:00:00Z"}}}
                """;
        assertThat(PhaseCalculator.compute(pod(base.formatted("false"))).phase()).isEqualTo(Phase.Starting);
        var running = PhaseCalculator.compute(pod(base.formatted("true")));
        assertThat(running.phase()).isEqualTo(Phase.Running);
        assertThat(running.ready()).isTrue();
    }

    @Test
    void exitCodesMapToStoppedOrFailed() {
        String base = """
                phase: Succeeded
                containerStatuses:
                - {name: server, state: {terminated: {exitCode: %d, reason: %s}}}
                """;
        assertThat(PhaseCalculator.compute(pod(base.formatted(0, "Completed"))).phase()).isEqualTo(Phase.Stopped);
        var crash = PhaseCalculator.compute(pod(base.formatted(137, "OOMKilled")));
        assertThat(crash.phase()).isEqualTo(Phase.Failed);
        assertThat(crash.reason()).isEqualTo("OOMKilled");
        assertThat(crash.exitCode()).isEqualTo(137);
    }

    @Test
    void imagePullErrorStaysPendingWithReason() {
        var r = PhaseCalculator.compute(pod("""
                phase: Pending
                initContainerStatuses:
                - {name: prepare, image: "x:y", state: {waiting: {reason: ImagePullBackOff, message: "not found"}}}
                """));
        assertThat(r.phase()).isEqualTo(Phase.Pending);
        assertThat(r.reason()).isEqualTo("ImagePullFailed");
    }

    @Test
    void deletingPodIsStopping() {
        Pod p = pod("phase: Running");
        p.getMetadata().setDeletionTimestamp("2026-10-15T10:00:00Z");
        assertThat(PhaseCalculator.compute(p).phase()).isEqualTo(Phase.Stopping);
    }
}
