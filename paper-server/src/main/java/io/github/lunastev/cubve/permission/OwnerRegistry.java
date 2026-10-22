package io.github.lunastev.cubve.permission;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** UUID-based ownership, independent of ops.json and permission attachments. */
public final class OwnerRegistry {
    private final Path file;
    private volatile Map<UUID, Entry> owners;

    public OwnerRegistry(final Path file) throws IOException {
        this.file = file.toAbsolutePath();
        this.owners = load();
    }

    public boolean contains(final UUID id) {
        return this.owners.containsKey(id);
    }

    public List<Entry> entries() {
        return this.owners.values().stream().sorted(Comparator.comparing(entry -> entry.id().toString())).toList();
    }

    public synchronized boolean add(final UUID id, final String name) throws IOException {
        if (contains(id)) {
            return false;
        }
        final Map<UUID, Entry> updated = new LinkedHashMap<>(this.owners);
        updated.put(id, new Entry(id, name));
        save(updated);
        return true;
    }

    public synchronized boolean remove(final UUID id) throws IOException {
        if (!contains(id)) {
            return false;
        }
        final Map<UUID, Entry> updated = new LinkedHashMap<>(this.owners);
        updated.remove(id);
        save(updated);
        return true;
    }

    private Map<UUID, Entry> load() throws IOException {
        if (!Files.exists(this.file)) {
            return Map.of();
        }
        try (var reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported owners.json schema");
            }
            final Map<UUID, Entry> loaded = new LinkedHashMap<>();
            for (var element : root.getAsJsonArray("owners")) {
                final JsonObject entry = element.getAsJsonObject();
                final String text = entry.get("uuid").getAsString();
                final UUID id = UUID.fromString(text);
                if (!id.toString().equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("Owner UUID must use the complete canonical format");
                }
                final String name = entry.get("name").getAsString();
                if (name.isBlank() || loaded.putIfAbsent(id, new Entry(id, name)) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate owner entry");
                }
            }
            return Map.copyOf(loaded);
        } catch (RuntimeException exception) {
            // Never replace a broken authorization file with an empty owner list.
            throw new IOException("Cannot load " + this.file + "; repair the owner file before starting Cubve", exception);
        }
    }

    private void save(final Map<UUID, Entry> updated) throws IOException {
        final JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        final JsonArray entries = new JsonArray();
        updated.values().stream().sorted(Comparator.comparing(entry -> entry.id().toString())).forEach(entry -> {
            final JsonObject object = new JsonObject();
            object.addProperty("uuid", entry.id().toString());
            object.addProperty("name", entry.name());
            entries.add(object);
        });
        root.add("owners", entries);
        final byte[] bytes = (new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
        final Path temporary = Files.createTempFile(this.file.getParent(), ".cubve-owners-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Publish authorization only after the complete replacement has succeeded.
            this.owners = Map.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Entry(UUID id, String name) {
        public Entry {
            java.util.Objects.requireNonNull(id, "id");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Owner name must be present");
            }
        }
    }
}
