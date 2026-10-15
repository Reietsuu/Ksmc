package io.ksmc.controller.software;

import io.ksmc.api.spi.ResolverContext;
import java.util.HashMap;
import java.util.Map;

/** Réponses HTTP simulées (URL → corps) ; les URL absentes répondent 404. */
final class FakeResolverContext implements ResolverContext {
    final Map<String, String> responses = new HashMap<>();

    FakeResolverContext on(String url, String body) {
        responses.put(url, body);
        return this;
    }

    @Override
    public String getString(String url) throws HttpStatusException {
        String body = responses.get(url);
        if (body == null) {
            throw new HttpStatusException(404, url);
        }
        return body;
    }
}
