package io.github.lunastev.cubve.chunk;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.WorldBorder;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Normal
class ChunkPregeneratorTest {
    @Test
    void admissionCountsFullChunksAndWaitsForTickAndLoadHeadroom() {
        MinecraftServer server = mock(MinecraftServer.class);
        server.server = mock(CraftServer.class);
        ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        CraftWorld world = mock(CraftWorld.class);
        when(world.getHandle()).thenReturn(level);
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any())).thenReturn(true);
        ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        when(level.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
        ChunkPregenerator service = new ChunkPregenerator(server);
        service.start(world, new PregenerationJob.Area(0, 0, 0, 0), 1);

        when(server.server.getAverageTickTime()).thenReturn(50.0);
        service.tick(System.nanoTime());
        assertEquals("waiting for tick headroom", service.job().reason());
        when(server.server.getAverageTickTime()).thenReturn(1.0);
        service.tick(System.nanoTime() - 40_000_000L);
        assertEquals("waiting for tick headroom", service.job().reason());
        when(level.getChunkSource().getFullChunksCount()).thenReturn(257);
        service.tick(System.nanoTime());
        assertEquals("waiting for normal chunk unloading", service.job().reason());
        verifyNoInteractions(scheduler);

        // Generation dependencies can contain many ProtoChunks; they are not full resident chunks.
        when(level.getChunkSource().getLoadedChunksCount()).thenReturn(10_000);
        when(level.getChunkSource().getFullChunksCount()).thenReturn(10);
        service.tick(System.nanoTime());
        assertTrue(service.job().inFlight());
        verify(scheduler).scheduleChunkLoad(eq(0), eq(0), eq(ChunkStatus.FULL), eq(true), eq(Priority.LOW), any());
    }
}
