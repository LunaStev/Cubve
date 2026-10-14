package io.github.lunastev.cubve.benchmark;

import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.objenesis.ObjenesisStd;
import org.openjdk.jmh.annotations.*;
import org.spigotmc.SpigotWorldConfig;

/** Actual lookup method and loaded-chunk map, without constructing a world or timing Mockito. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HopperLookupBenchmark {
    private static final MethodHandle LOOKUP;
    static {
        try {
            LOOKUP = MethodHandles.privateLookupIn(HopperBlockEntity.class, MethodHandles.lookup())
                .findStatic(HopperBlockEntity.class, "getBlockContainer",
                    MethodType.methodType(Container.class, Level.class, BlockPos.class, BlockState.class));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Param({"AIR", "STONE"})
    public String block;
    @Param({"false", "true"})
    public boolean canLoadChunks;
    private Level level;
    private BlockState state;
    private final BlockPos[] positions = new BlockPos[32];
    private int cursor;

    private static void field(Class<?> owner, String name, Object target, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setup() throws Exception {
        new AllFeaturesExtension().init(null);
        // Only fields used by the non-container path are initialized. These are real, uninstrumented
        // instances: Level.hasChunkAt -> ServerLevel.getChunkIfLoaded -> ServerChunkCache -> real map.
        ObjenesisStd instances = new ObjenesisStd();
        ServerLevel world = instances.newInstance(ServerLevel.class);
        ServerChunkCache chunks = instances.newInstance(ServerChunkCache.class);
        SpigotWorldConfig config = instances.newInstance(SpigotWorldConfig.class);
        config.hopperCanLoadChunks = this.canLoadChunks;
        var loaded = new ConcurrentChainedLong2ReferenceHashTable<LevelChunk>();
        LevelChunk sentinel = instances.newInstance(LevelChunk.class); // Only used as a non-null map value.
        for (int i = 0; i < this.positions.length; i++) {
            this.positions[i] = new BlockPos(i * 16 + 1, 120, (i % 4) * 16 + 1);
            loaded.put(ChunkPos.pack(i, i % 4), sentinel);
        }
        field(ServerChunkCache.class, "fullChunks", chunks, loaded);
        field(ServerLevel.class, "chunkSource", world, chunks);
        field(Level.class, "spigotConfig", world, config);
        this.level = world;
        this.state = "AIR".equals(this.block) ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    @Benchmark
    public Container nonContainerLookup() throws Throwable {
        BlockPos pos = this.positions[this.cursor++ & 31];
        return (Container) LOOKUP.invokeExact(this.level, pos, this.state);
    }
}
