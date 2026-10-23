package io.github.lunastev.cubve.benchmark;

import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.*;

/** Fresh actual ProtoChunks including all section palettes, ticking lists and light arrays. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class EmptyChunkBenchmark {
    @Param({"384", "2048"})
    public int height;
    private LevelHeightAccessor heights;
    private PalettedContainerFactory containers;

    @Setup(Level.Trial)
    public void setup() {
        new AllFeaturesExtension().init(null);
        this.heights = LevelHeightAccessor.create(this.height == 384 ? -64 : -1024, this.height);
        this.containers = PalettedContainerFactory.create(RegistryHelper.registryAccess());
    }

    @Benchmark
    public ProtoChunk allocateFreshChunk() {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, this.heights, this.containers, null);
    }
}
