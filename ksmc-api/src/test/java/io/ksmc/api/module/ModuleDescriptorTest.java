package io.ksmc.api.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModuleDescriptorTest {

    static ModuleDescriptor read(String json) throws Exception {
        return ModuleDescriptor.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsDescriptorAndDefaultsApiToCurrentMajor() throws Exception {
        ModuleDescriptor d = read("""
                {"id": "hello", "name": "Hello", "version": "1.2.0", "main": "a.Hello", "extra": 1,
                 "dependencies": [{"id": "base", "version": "^2.0", "optional": true}]}""");
        assertThat(d.semVer()).isEqualTo(new SemVer(1, 2, 0, null));
        assertThat(d.dependencies).singleElement().satisfies(dep -> assertThat(dep.optional).isTrue());
        assertThat(d.compatibleWith(KsmcApiVersion.CURRENT)).isTrue();
        assertThat(d.compatibleWith(new SemVer(KsmcApiVersion.CURRENT.major() + 1, 0, 0, null))).isFalse();
    }

    @Test
    void newerApiMajorIsIncompatible() throws Exception {
        ModuleDescriptor d = read("""
                {"id": "next", "version": "1.0.0", "main": "a.B", "apiVersion": "^%d.0"}"""
                .formatted(KsmcApiVersion.CURRENT.major() + 1));
        assertThat(d.compatibleWith(KsmcApiVersion.CURRENT)).isFalse();
    }

    @Test
    void invalidDescriptorsAreRejected() {
        assertThatThrownBy(() -> read("{\"id\": \"Bad_Id\", \"version\": \"1.0.0\", \"main\": \"a.B\"}"))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> read("{\"id\": \"ok\", \"version\": \"1.0.0\"}")).hasMessageContaining("main");
        assertThatThrownBy(() -> read("{\"id\": \"ok\", \"version\": \"x\", \"main\": \"a.B\"}"))
                .hasMessageContaining("version");
        assertThatThrownBy(() -> read("{\"id\": \"ok\", \"version\": \"1.0.0\", \"main\": \"a.B\","
                + " \"dependencies\": [{\"id\": \"ok\"}]}")).hasMessageContaining("dépendance");
    }
}
