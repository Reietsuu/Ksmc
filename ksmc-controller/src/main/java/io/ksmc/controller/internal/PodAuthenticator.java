package io.ksmc.controller.internal;

import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.api.model.authentication.UserInfo;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentifie les Pods (services, jobs de build) grâce à leur jeton de ServiceAccount projeté
 * (audience {@value #AUDIENCE}), validé par l'API Kubernetes (TokenReview). Résultats mis en cache 60 s.
 */
public final class PodAuthenticator {
    public static final String AUDIENCE = "ksmc";
    private static final String SA_PREFIX = "system:serviceaccount:";
    private static final String POD_NAME_EXTRA = "authentication.kubernetes.io/pod-name";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final KubernetesClient client;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<PodIdentity> identity, Instant expires) {
    }

    public PodAuthenticator(KubernetesClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public Optional<PodIdentity> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Cached cached = cache.get(bearerToken);
        if (cached != null && cached.expires().isAfter(now)) {
            return cached.identity();
        }
        if (cache.size() > 10_000) {
            cache.values().removeIf(c -> c.expires().isBefore(now));
        }
        Optional<PodIdentity> identity = review(bearerToken);
        cache.put(bearerToken, new Cached(identity, now.plus(CACHE_TTL)));
        return identity;
    }

    private Optional<PodIdentity> review(String token) {
        TokenReview review = client.resource(new TokenReviewBuilder()
                .withNewSpec().withToken(token).withAudiences(AUDIENCE).endSpec().build()).create();
        if (review.getStatus() == null || !Boolean.TRUE.equals(review.getStatus().getAuthenticated())) {
            return Optional.empty();
        }
        UserInfo user = review.getStatus().getUser();
        if (user == null || user.getUsername() == null || !user.getUsername().startsWith(SA_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = user.getUsername().substring(SA_PREFIX.length()).split(":", 2);
        List<String> pod = user.getExtra() == null ? null : user.getExtra().get(POD_NAME_EXTRA);
        if (parts.length != 2 || pod == null || pod.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PodIdentity(parts[0], parts[1], pod.getFirst()));
    }
}
