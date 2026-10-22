package io.ksmc.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferPlanTest {
    static final TransferPlan.Candidate MODERN = new TransferPlan.Candidate("u1", 770, "play.example.com", 25577);
    static final TransferPlan.Candidate OLD = new TransferPlan.Candidate("u2", 47, "play.example.com", 25565);
    static final TransferPlan.Candidate NO_HOST = new TransferPlan.Candidate("u3", 770, null, 0);

    @Test
    void modernClientsAreTransferredToTheAddressTheyUsed() {
        List<TransferPlan.Step> steps = TransferPlan.plan(List.of(MODERN, OLD), true, Map.of());
        assertThat(steps.get(0).isTransfer()).isTrue();
        assertThat(steps.get(0).host).isEqualTo("play.example.com");
        assertThat(steps.get(0).port).isEqualTo(25577);
        assertThat(steps.get(1).isTransfer()).isFalse();
        assertThat(steps.get(1).message).isEqualTo("Le proxy redémarre, reconnecte-toi.");
    }

    @Test
    void configuredAddressWins() {
        TransferPlan.Step s = TransferPlan.plan(List.of(MODERN, NO_HOST), true,
                Map.of("ksmc.transfer.address", "mc.example.net:30565")).get(1);
        assertThat(s.host).isEqualTo("mc.example.net");
        assertThat(s.port).isEqualTo(30565);
        assertThat(TransferPlan.plan(List.of(NO_HOST), true, Map.of("ksmc.transfer.address", "mc.example.net")).get(0).port)
                .isEqualTo(25565);
    }

    @Test
    void lastProxyOrDisabledTransferKicksEveryone() {
        assertThat(TransferPlan.plan(List.of(MODERN), false, Map.of()).get(0).isTransfer()).isFalse();
        TransferPlan.Step s = TransferPlan.plan(List.of(MODERN), true,
                Map.of("ksmc.transfer.enabled", "false", "ksmc.transfer.fallback-message", "Bye")).get(0);
        assertThat(s.isTransfer()).isFalse();
        assertThat(s.message).isEqualTo("Bye");
    }

    @Test
    void unknownAddressFallsBackToKick() {
        assertThat(TransferPlan.plan(List.of(NO_HOST), true, Map.of()).get(0).isTransfer()).isFalse();
    }
}
