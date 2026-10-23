package io.github.lunastev.cubve.chunk;

import ca.spottedleaf.concurrentutil.util.Priority;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.craftbukkit.CraftWorld;

/** Native chunk scheduler integration; never joins a future or retains completed chunks. */
public final class ChunkPregenerator {
    private final MinecraftServer server;
    private ServerLevel world;
    private PregenerationJob job;
    private int initialLoaded;

    public ChunkPregenerator(MinecraftServer server) { this.server = server; }

    public void start(CraftWorld world, PregenerationJob.Area area, int rate) {
        if (this.job != null && this.job.active()) throw new IllegalStateException("A job is already active; cancel it and wait for its pending request");
        if (!world.getWorldBorder().isInside(new org.bukkit.Location(world, area.minX() * 16.0, 0, area.minZ() * 16.0))
            || !world.getWorldBorder().isInside(new org.bukkit.Location(world, area.maxX() * 16.0 + 15, 0, area.maxZ() * 16.0 + 15))) {
            throw new IllegalArgumentException("Every requested chunk must be inside the world border");
        }
        this.world = world.getHandle();
        this.initialLoaded = this.world.getChunkSource().getFullChunksCount();
        this.job = new PregenerationJob(area, rate);
    }

    public void tick(long tickStart) {
        if (this.job == null || this.job.state() != PregenerationJob.State.RUNNING || this.job.inFlight()) return;
        if (this.server.getLevel(this.world.dimension()) != this.world) {
            this.job.fail("world unloaded");
            return;
        }
        final long now = System.nanoTime();
        final Runtime runtime = Runtime.getRuntime();
        String blocked = "";
        if (now - tickStart >= 35_000_000L || this.server.server.getAverageTickTime() >= 40.0) blocked = "waiting for tick headroom";
        else if (runtime.totalMemory() - runtime.freeMemory() >= runtime.maxMemory() * 0.85) blocked = "waiting for heap headroom";
        else if (this.world.getChunkSource().getFullChunksCount() > this.initialLoaded + 256) blocked = "waiting for normal chunk unloading";
        this.job.tick(now, blocked, (x, z, completion) -> {
            // The temporary scheduler ticket is released in the scheduler's callback finally block.
            this.world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(x, z, ChunkStatus.FULL, true, Priority.LOW,
                chunk -> completion.accept(chunk != null));
        });
    }

    public boolean keepsServerAwake() { return this.job != null && this.job.keepsServerAwake(); }
    public PregenerationJob job() { return this.job; }
    public String worldName() { return this.world == null ? "" : this.world.getWorld().getName(); }
}
