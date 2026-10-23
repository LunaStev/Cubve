package io.github.lunastev.cubve.benchmark;

import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Real heightmap updates/section reads; no timed mocks, world loading or block mutation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HeightmapRemovalBenchmark {
    @Param({"normal-gap", "extended-gap", "extended-bottom-gap", "extended-dense"})
    public String workload;
    private Heightmap map;
    private int top;

    @Setup(Level.Trial)
    public void setup() {
        new AllFeaturesExtension().init(null);
        boolean normal = this.workload.equals("normal-gap");
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
            LevelHeightAccessor.create(normal ? -64 : -1024, normal ? 384 : 2048),
            PalettedContainerFactory.create(RegistryHelper.registryAccess()), null);
        this.top = normal ? 319 : 1000;
        int floor = this.workload.equals("extended-bottom-gap") ? -1000 : 64;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (this.workload.equals("extended-dense")) {
                    for (int y = floor; y < this.top; y++) {
                        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z,
                            Blocks.STONE.defaultBlockState());
                    }
                }
                chunk.getSection(chunk.getSectionIndex(floor)).setBlockState(x, floor & 15, z,
                    Blocks.STONE.defaultBlockState());
            }
        }
        // The dense fixture fills the whole footprint, giving an immediate-hit control.
        this.map = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING);
    }

    @Benchmark
    @OperationsPerInvocation(256)
    public void removeTop(Blackhole blackhole) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                this.map.update(x, this.top, z, Blocks.STONE.defaultBlockState());
                blackhole.consume(this.map.update(x, this.top, z, Blocks.AIR.defaultBlockState()));
                blackhole.consume(this.map.getFirstAvailable(x, z));
            }
        }
    }
}
