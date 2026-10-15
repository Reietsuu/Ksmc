package io.ksmc.controller.internal;

/** Identité d'un Pod appelant l'API interne, établie par TokenReview. */
public record PodIdentity(String namespace, String serviceAccount, String podName) {
}
