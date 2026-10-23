package io.github.lunastev.cubve.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Normal
class PregenerationJobTest {
    @Test
    void coversNegativeAreaOnceWithoutQueuingOrRetainingEveryChunk() {
        PregenerationJob job = new PregenerationJob(new PregenerationJob.Area(-2, -1, 1, 1), 4);
        List<String> visited = new ArrayList<>();
        List<Consumer<Boolean>> callbacks = new ArrayList<>();
        PregenerationJob.Loader loader = (x, z, callback) -> { visited.add(x + "," + z); callbacks.add(callback); };
        for (int i = 0; i < 12; i++) {
            job.tick(i * 1_000_000_000L, "", loader);
            assertTrue(job.inFlight());
            job.tick((i + 1) * 1_000_000_000L, "", loader);
            assertEquals(i + 1, visited.size(), "At most one request may be pending");
            callbacks.getLast().accept(true);
            callbacks.getLast().accept(true);
            assertEquals(i + 1, job.completed());
        }
        assertEquals(List.of("-2,-1", "-1,-1", "0,-1", "1,-1", "-2,0", "-1,0", "0,0", "1,0",
            "-2,1", "-1,1", "0,1", "1,1"), visited);
        assertEquals(PregenerationJob.State.COMPLETED, job.state());
        job.tick(Long.MAX_VALUE, "", loader);
        assertEquals(12, visited.size());
        assertFalse(job.keepsServerAwake());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void rateLimitUsesElapsedTimeAndDoesNotBurstAfterPause(int rate) {
        PregenerationJob job = new PregenerationJob(new PregenerationJob.Area(0, 0, 9, 9), rate);
        List<String> requests = new ArrayList<>();
        PregenerationJob.Loader loader = (x, z, callback) -> { requests.add(x + "," + z); callback.accept(true); };
        job.tick(0, "", loader);
        job.tick(1_000_000_000L / rate - 1, "", loader);
        assertEquals(1, requests.size());
        job.tick(1_000_000_000L / rate, "", loader);
        assertEquals(2, requests.size());
        job.tick(100_000_000_000L, "", loader);
        job.tick(100_000_000_000L, "", loader);
        assertEquals(3, requests.size(), "A long delay must not create a catch-up burst");
    }

    @Test
    void resourceBackpressureAndOperatorPauseStopNewRequests() {
        PregenerationJob job = new PregenerationJob(new PregenerationJob.Area(0, 0, 2, 2), 1);
        List<Consumer<Boolean>> callbacks = new ArrayList<>();
        PregenerationJob.Loader loader = (x, z, callback) -> callbacks.add(callback);
        job.tick(0, "waiting for heap headroom", loader);
        assertTrue(callbacks.isEmpty());
        assertEquals("waiting for heap headroom", job.reason());
        job.tick(0, "", loader);
        job.pause();
        callbacks.getFirst().accept(true);
        assertEquals(PregenerationJob.State.PAUSED, job.state());
        assertFalse(job.keepsServerAwake());
        job.tick(2_000_000_000L, "", loader);
        assertEquals(1, callbacks.size());
        job.resume();
        job.tick(2_000_000_000L, "waiting for tick headroom", loader);
        assertEquals(1, callbacks.size());
        job.tick(2_000_000_000L, "", loader);
        assertEquals(2, callbacks.size());
    }

    @Test
    void cancellationDrainsPendingRequestWithoutContinuingOrReleasingOtherTickets() {
        PregenerationJob job = new PregenerationJob(new PregenerationJob.Area(0, 0, 2, 2), 1);
        List<Consumer<Boolean>> callbacks = new ArrayList<>();
        job.tick(0, "", (x, z, callback) -> callbacks.add(callback));
        job.cancel();
        assertTrue(job.active());
        callbacks.getFirst().accept(true);
        assertFalse(job.active());
        assertEquals(PregenerationJob.State.CANCELLED, job.state());
        job.resume();
        job.tick(2_000_000_000L, "", (x, z, callback) -> fail("Cancelled job must not issue requests"));
    }

    @Test
    void rejectedAndThrowingLoadsFailWithoutLeakingAdmissionSlot() {
        for (boolean throwing : new boolean[]{false, true}) {
            PregenerationJob job = new PregenerationJob(new PregenerationJob.Area(0, 0, 0, 0), 1);
            job.tick(0, "", (x, z, callback) -> {
                if (throwing) throw new IllegalStateException("fixture");
                callback.accept(false);
            });
            assertEquals(PregenerationJob.State.FAILED, job.state());
            assertFalse(job.active());
            assertEquals(0, job.completed());
        }
    }

    @Test
    void boundsRejectOverflowAndExcessiveWorkAndPreserveChunkRounding() {
        assertEquals(new PregenerationJob.Area(-2, -1, 0, 1), PregenerationJob.Area.around(-1, 0, 16));
        assertEquals(1, PregenerationJob.Area.around(0, 0, 0).size());
        assertThrows(IllegalArgumentException.class, () -> PregenerationJob.Area.around(Integer.MAX_VALUE, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> PregenerationJob.Area.around(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> PregenerationJob.Area.around(0, 0, 100_001));
        assertThrows(IllegalArgumentException.class, () -> PregenerationJob.Area.around(0, 0, 100_000));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationJob.Area(1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationJob(new PregenerationJob.Area(0, 0, 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new PregenerationJob(new PregenerationJob.Area(0, 0, 0, 0), 5));
    }
}
