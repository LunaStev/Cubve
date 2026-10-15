package io.github.lunastev.cubve.benchmark;

import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Real noise-volume sampling, aquifers, block palette writes, WG heightmaps and fluid postprocessing.
 * Registry and RandomState construction belong to Trial setup. Each invocation gets fresh, empty
 * sections and fresh NoiseChunk caches, so a previous fill cannot turn this into a no-op overwrite.
 *
 * Invocation setup/teardown are excluded from the reported fill time. The GC profiler observes
 * their allocations as well, however: allocation/op describes the fresh fill fixture as a whole,
 * and must not be reported as allocations made solely by doFill. Surface rules, carvers, biome
 * population, structure blending, lighting, scheduling and serialization are outside this fixture.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class ChunkFillBenchmark {
    @Param({"overworld"})
    public String dimension;

    @Param({"0,0", "64,-32", "-65,33"})
    public String coordinates;

    private ChunkFillBenchmarkFixtureTest.Fixture fixture;
    private ChunkPos position;
    private ChunkFillBenchmarkFixtureTest.PreparedChunk prepared;

    @Setup(Level.Trial)
    public void setupTrial() throws Throwable {
        new AllFeaturesExtension().init(null);
        this.fixture = new ChunkFillBenchmarkFixtureTest.Fixture(this.dimension, 8142026L);
        String[] parts = this.coordinates.split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected chunk coordinates x,z: " + this.coordinates);
        }
        this.position = new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        this.prepared = this.fixture.prepare(this.position);
    }

    @Benchmark
    public void fillFreshChunk(Blackhole blackhole) throws Throwable {
        this.fixture.fill(this.prepared);
        // The output escapes through JMH's Blackhole. Full content hashing is deliberately left
        // to the fixture regression tests so hashing 98,304 block states is not part of fill time.
        blackhole.consume(this.prepared.chunk());
    }

    @TearDown(Level.Invocation)
    public void teardownInvocation() {
        this.prepared.close();
        this.prepared = null;
    }
}
