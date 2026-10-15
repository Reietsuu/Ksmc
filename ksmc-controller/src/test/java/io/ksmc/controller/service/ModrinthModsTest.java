package io.ksmc.controller.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.ksmc.api.spi.ResolverContext;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class ModrinthModsTest {

    @Test
    void picksPrimaryFileOfLatestCompatibleVersion() throws Exception {
        ResolverContext http = url -> url.contains("fabricproxy-lite") && url.contains("26.3")
                ? """
                  [{"id":"CsEpiziv","version_number":"v2.12.0","files":[
                    {"filename":"FabricProxy-Lite-2.12.0-sources.jar","url":"https://cdn/x-sources.jar","primary":false},
                    {"filename":"FabricProxy-Lite-2.12.0.jar","url":"https://cdn/x.jar","primary":true}]}]"""
                : "[]";
        ModrinthMods mods = new ModrinthMods(http, Clock.systemUTC());

        var plan = mods.latest("fabricproxy-lite", "fabric", "26.3").orElseThrow();
        assertThat(plan.url()).isEqualTo("https://cdn/x.jar");
        assertThat(plan.target()).isEqualTo("mods/FabricProxy-Lite-2.12.0.jar");
        assertThat(plan.cacheKey()).isEqualTo("modrinth/fabricproxy-lite/CsEpiziv/FabricProxy-Lite-2.12.0.jar");
        assertThat(mods.latest("fabricproxy-lite", "fabric", "1.0")).isEmpty();
    }
}
