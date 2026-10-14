package io.github.lunastev.cubve;

import io.github.lunastev.cubve.profiling.TickProfiler;
import java.nio.file.Files;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Normal
class TickProfilerTest {
    @Test
    void ordinaryServerDoesNotRecordCustomTickEvents() throws Exception {
        assertFalse(Boolean.getBoolean("cubve.profile.ticks"));
        final var file = Files.createTempFile("cubve-ticks-disabled", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(TickProfiler.TickEvent.class);
            recording.start();
            TickProfiler.recordTick(1_000_000L);
            recording.stop();
            recording.dump(file);
            assertTrue(RecordingFile.readAllEvents(file).stream()
                .noneMatch(event -> event.getEventType().getName().equals("cubve.ServerTick")));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
