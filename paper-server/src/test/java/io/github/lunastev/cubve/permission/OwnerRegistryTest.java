package io.github.lunastev.cubve.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Normal
class OwnerRegistryTest {
    @TempDir Path directory;
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void uuidOwnershipSurvivesRestartAndRemoval() throws IOException {
        final Path path = this.directory.resolve("owners.json");
        final OwnerRegistry first = new OwnerRegistry(path);
        assertFalse(first.contains(OWNER));
        assertTrue(first.add(OWNER, "OriginalName"));
        assertFalse(first.add(OWNER, "ChangedName"));
        final OwnerRegistry restarted = new OwnerRegistry(path);
        assertTrue(restarted.contains(OWNER));
        assertEquals("OriginalName", restarted.entries().getFirst().name());
        assertThrows(UnsupportedOperationException.class, () -> restarted.entries().clear());
        assertTrue(restarted.remove(OWNER));
        assertFalse(new OwnerRegistry(path).contains(OWNER));
        assertFalse(restarted.remove(OWNER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{\"schemaVersion\":2,\"owners\":[]}",
        "{\"schemaVersion\":1}", "{\"schemaVersion\":1,\"owners\":[{\"uuid\":\"1-1-1-1-1\",\"name\":\"bad\"}]}",
        "{\"schemaVersion\":1,\"owners\":[{\"uuid\":\"11111111-1111-4111-8111-111111111111\"}]}"})
    void malformedAuthorityFileStopsLoadingWithoutReplacingIt(final String input) throws IOException {
        final Path path = this.directory.resolve("owners.json");
        Files.writeString(path, input);
        assertThrows(IOException.class, () -> new OwnerRegistry(path));
        assertEquals(input, Files.readString(path));
    }

    @Test
    void duplicateUuidIsRejected() throws IOException {
        final Path path = this.directory.resolve("owners.json");
        Files.writeString(path, "{\"schemaVersion\":1,\"owners\":[{\"uuid\":\"" + OWNER
            + "\",\"name\":\"a\"},{\"uuid\":\"" + OWNER + "\",\"name\":\"b\"}]}");
        assertThrows(IOException.class, () -> new OwnerRegistry(path));
    }

    @Test
    void failedWriteDoesNotGrantOwnershipOrLeaveTemporaryFiles() throws IOException {
        final Path path = this.directory.resolve("owners.json");
        final OwnerRegistry registry = new OwnerRegistry(path);
        Files.createDirectory(path);
        assertThrows(IOException.class, () -> registry.add(OWNER, "Owner"));
        assertFalse(registry.contains(OWNER));
        try (var files = Files.list(this.directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void failedWriteDoesNotRevokeOwnership() throws IOException {
        final Path path = this.directory.resolve("owners.json");
        final OwnerRegistry registry = new OwnerRegistry(path);
        registry.add(OWNER, "Owner");
        Files.move(path, this.directory.resolve("previous.json"));
        Files.createDirectory(path);
        assertThrows(IOException.class, () -> registry.remove(OWNER));
        assertTrue(registry.contains(OWNER));
    }
}
