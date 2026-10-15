package io.ksmc.controller.messaging;

import io.ksmc.wire.messaging.CloudEvent;

/** Publication des événements cloud par le controller (sans effet si la messagerie est indisponible). */
@FunctionalInterface
public interface CloudEvents {
    void publish(CloudEvent event);

    CloudEvents NONE = event -> { };
}
