package io.ksmc.controller.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileArtifactStorageTest {
    @TempDir
    Path dir;
    FileArtifactStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new FileArtifactStorage(dir);
    }

    @Test
    void writeReadListDelete() throws IOException {
        storage.write("templates/lobby/default/server.properties", in("motd=hi"));
        storage.write("templates/lobby/default/plugins/a.jar", in("jar"));
        storage.write("artifacts/paper/1.21.4.jar", in("paper"));

        assertThat(read("templates/lobby/default/server.properties")).isEqualTo("motd=hi");
        assertThat(storage.list("templates/lobby")).containsExactly(
                "templates/lobby/default/plugins/a.jar", "templates/lobby/default/server.properties");
        assertThat(storage.size("artifacts/paper/1.21.4.jar")).isEqualTo(5);

        storage.delete("templates/lobby/");
        assertThat(storage.list("templates")).isEmpty();
        assertThat(storage.exists("artifacts/paper/1.21.4.jar")).isTrue();
    }

    @Test
    void overwriteReplacesContent() throws IOException {
        storage.write("a/b.txt", in("one"));
        storage.write("a/b.txt", in("two"));
        assertThat(read("a/b.txt")).isEqualTo("two");
        assertThat(storage.list("")).containsExactly("a/b.txt");
    }

    @Test
    void missingKeyIsEmpty() throws IOException {
        assertThat(storage.open("nope")).isEmpty();
        assertThat(storage.size("nope")).isEqualTo(-1);
        assertThat(storage.list("nothing/here")).isEmpty();
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> storage.write("../escape.txt", in("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("a/../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leadingSlashIsRelative() throws IOException {
        storage.write("/x/y.txt", in("ok"));
        assertThat(storage.exists("x/y.txt")).isTrue();
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String key) throws IOException {
        try (InputStream in = storage.open(key).orElseThrow()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
