package io.github.lunastev.cubve.chunk;

import java.util.function.Consumer;

/** Constant-space, single-request admission control. Access only from the server thread. */
public final class PregenerationJob {
    public enum State { RUNNING, PAUSED, CANCELLED, FAILED, COMPLETED }

    public record Area(int minX, int minZ, int maxX, int maxZ) {
        public Area {
            if (minX > maxX || minZ > maxZ || minX < -1_874_999 || minZ < -1_874_999
                || maxX > 1_874_998 || maxZ > 1_874_998 || size(minX, minZ, maxX, maxZ) > 1_000_000L) {
                throw new IllegalArgumentException("Area must stay inside the world and contain at most 1,000,000 chunks");
            }
        }

        private static long size(int minX, int minZ, int maxX, int maxZ) {
            return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        }

        public long size() { return size(this.minX, this.minZ, this.maxX, this.maxZ); }

        public static Area around(int blockX, int blockZ, int radius) {
            if (radius < 0 || radius > 100_000 || Math.abs((long) blockX) + radius > 29_999_968L
                || Math.abs((long) blockZ) + radius > 29_999_968L) {
                throw new IllegalArgumentException("Invalid centre/radius or area outside world bounds");
            }
            return new Area((blockX - radius) >> 4, (blockZ - radius) >> 4,
                (blockX + radius) >> 4, (blockZ + radius) >> 4);
        }
    }

    @FunctionalInterface
    public interface Loader {
        void load(int x, int z, Consumer<Boolean> completion);
    }

    private final Area area;
    private final long intervalNanos;
    private long issued;
    private long completed;
    private long lastRequest;
    private boolean inFlight;
    private State state = State.RUNNING;
    private String reason = "ready";

    public PregenerationJob(Area area, int chunksPerSecond) {
        if (chunksPerSecond < 1 || chunksPerSecond > 4) throw new IllegalArgumentException("Rate must be 1..4 chunks/second");
        this.area = java.util.Objects.requireNonNull(area);
        this.intervalNanos = 1_000_000_000L / chunksPerSecond;
    }

    public void tick(long now, String blockedBy, Loader loader) {
        if (this.state != State.RUNNING || this.inFlight) return;
        this.reason = blockedBy;
        if (!blockedBy.isEmpty() || this.issued != 0 && now - this.lastRequest < this.intervalNanos) return;
        final long index = this.issued++;
        final long width = (long) this.area.maxX - this.area.minX + 1;
        final int x = this.area.minX + (int) (index % width);
        final int z = this.area.minZ + (int) (index / width);
        this.lastRequest = now;
        this.inFlight = true;
        this.reason = "loading " + x + "," + z;
        try {
            loader.load(x, z, success -> complete(index, success));
        } catch (RuntimeException exception) {
            complete(index, false);
        }
    }

    private void complete(long index, boolean success) {
        // A late/duplicate callback cannot count twice or complete a later request.
        if (!this.inFlight || index != this.issued - 1) return;
        this.inFlight = false;
        if (success) this.completed++;
        if (this.state == State.CANCELLED) return;
        if (!success) {
            this.state = State.FAILED;
            this.reason = "chunk request failed; inspect server log";
        } else if (this.completed == this.area.size()) {
            this.state = State.COMPLETED;
            this.reason = "generated; normal saving/unloading still applies";
        }
    }

    public void pause() { if (this.state == State.RUNNING) { this.state = State.PAUSED; this.reason = "operator pause"; } }
    public void resume() { if (this.state == State.PAUSED) { this.state = State.RUNNING; this.reason = "ready"; } }
    public void cancel() { if (this.state == State.RUNNING || this.state == State.PAUSED) { this.state = State.CANCELLED; this.reason = "cancelled; current request may finish"; } }
    public void fail(String reason) { this.state = State.FAILED; this.reason = reason; }
    public State state() { return this.state; }
    public String reason() { return this.reason; }
    public long completed() { return this.completed; }
    public long total() { return this.area.size(); }
    public boolean inFlight() { return this.inFlight; }
    public boolean active() { return this.state == State.RUNNING || this.state == State.PAUSED || this.inFlight; }
    public boolean keepsServerAwake() { return this.state == State.RUNNING || this.inFlight; }
}
