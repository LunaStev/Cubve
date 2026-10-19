package io.github.lunastev.cubve.benchmark;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Actual section palette writes and bookkeeping, without worlds, plugins or timed test doubles.
 * Each invocation visits all 4,096 existing cells. JMH normalizes time/allocation to one write.
 * Changed writes alternate stone/glass on every pass; mixed writes change one quarter of cells.
 * Same-state writes retain their state across passes, without invocation setup or allocation.
 * This excludes chunk lookup, heightmaps, lighting, physics, networking and WorldEdit itself.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ChunkSectionWriteBenchmark {
    private static final int CELL_COUNT = 16 * 16 * 16;

    @Param({"same-stone", "same-water", "same-leaves", "changed", "mixed"})
    public String workload;

    @Param({"true"})
    public boolean checkThreading;

    private LevelChunkSection section;
    private final BlockState[] first = new BlockState[CELL_COUNT];
    private final BlockState[] second = new BlockState[CELL_COUNT];
    private boolean nextFirst;

    @Setup(Level.Trial)
    public void setup() {
        new AllFeaturesExtension().init(null);
        this.section = new LevelChunkSection(PalettedContainerFactory.create(RegistryHelper.registryAccess()), null, null, 0);
        BlockState initial = switch (this.workload) {
            case "same-stone", "changed", "mixed" -> Blocks.STONE.defaultBlockState();
            case "same-water" -> Blocks.WATER.defaultBlockState();
            case "same-leaves" -> Blocks.OAK_LEAVES.defaultBlockState();
            default -> throw new IllegalArgumentException("Unknown write workload: " + this.workload);
        };
        Arrays.fill(this.first, initial);
        Arrays.fill(this.second, initial);
        for (int i = 0; i < CELL_COUNT; i++) {
            if (this.workload.equals("changed") || this.workload.equals("mixed") && (i & 3) == 0) {
                this.second[i] = Blocks.GLASS.defaultBlockState();
            }
            this.section.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, initial, this.checkThreading);
        }
        // Start with the second request so changed/mixed cases really change on every pass.
        this.nextFirst = false;
    }

    @Benchmark
    @OperationsPerInvocation(CELL_COUNT)
    public void writeExistingSection(Blackhole blackhole) {
        BlockState[] requested = this.nextFirst ? this.first : this.second;
        this.nextFirst = !this.nextFirst;
        for (int i = 0; i < CELL_COUNT; i++) {
            blackhole.consume(this.section.setBlockState(i & 15, i >>> 8, (i >>> 4) & 15, requested[i], this.checkThreading));
        }
    }
}
