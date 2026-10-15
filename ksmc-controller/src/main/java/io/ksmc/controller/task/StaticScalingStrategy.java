package io.ksmc.controller.task;

import io.ksmc.api.spi.ScalingStrategy;

/** Stratégie par défaut : {@code minServiceCount} + services démarrés manuellement. */
public final class StaticScalingStrategy implements ScalingStrategy {
    public static final String NAME = "static";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int desiredServices(Context context) {
        var spec = context.task().getSpec();
        return spec.minServiceCount + spec.extraServices;
    }
}
