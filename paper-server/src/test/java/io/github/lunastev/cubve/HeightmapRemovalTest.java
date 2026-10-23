package io.github.lunastev.cubve;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@AllFeatures
class HeightmapRemovalTest {
    @ParameterizedTest
    @EnumSource(Heightmap.Types.class)
    void removalsMatchIndependentColumnScanAcrossGapsAndBoundaries(Heightmap.Types type) {
        for (int[] range : new int[][]{{-64, 384}, {-1024, 2048}, {-63, 380}}) {
            for (boolean occupiedNeighbours : new boolean[]{false, true}) {
                ProtoChunk chunk = new ProtoChunk(new ChunkPos(-2, 3), UpgradeData.EMPTY,
                    LevelHeightAccessor.create(range[0], range[1]),
                    PalettedContainerFactory.create(RegistryHelper.registryAccess()), null);
                Heightmap map = new Heightmap(chunk, type);
                BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                    Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                    Blocks.CAVE_AIR.defaultBlockState(), Blocks.VOID_AIR.defaultBlockState(), Blocks.AIR.defaultBlockState()};
                Random random = new Random(8142026);
                // Include negative coordinates, partial sections, section edges and long empty gaps.
                for (int i = 0; i < 192; i++) {
                    int y = chunk.getMinY() + random.nextInt(chunk.getHeight());
                    write(chunk, 3, y, 7, states[random.nextInt(states.length)]);
                }
                // Occupied neighbouring columns must never make an empty target column opaque.
                if (occupiedNeighbours) {
                    for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y += 16) {
                        write(chunk, 4, y, 7, Blocks.STONE.defaultBlockState());
                    }
                }
                for (int top : new int[]{chunk.getMaxY(), chunk.getMaxY() - 8, 31, 16, 15, 0, -1, -16, -17}) {
                    if (chunk.isOutsideBuildHeight(top)) continue;
                    // Clear all blocks above the test surface, then rebuild its exact initial map.
                    for (int y = top; y <= chunk.getMaxY(); y++) write(chunk, 3, y, 7, Blocks.AIR.defaultBlockState());
                    map = new Heightmap(chunk, type);
                    for (int y = chunk.getMinY(); y < top; y++) map.update(3, y, 7, chunk.getBlockState(3, y, 7));
                    write(chunk, 3, top, 7, Blocks.STONE.defaultBlockState());
                    map.update(3, top, 7, Blocks.STONE.defaultBlockState());
                    write(chunk, 3, top, 7, Blocks.AIR.defaultBlockState());
                    map.update(3, top, 7, Blocks.AIR.defaultBlockState());
                    assertEquals(scan(chunk, type, 3, 7), map.getFirstAvailable(3, 7), type + " top=" + top);
                }
                for (int y = chunk.getMinY(); y <= chunk.getMaxY(); y++) write(chunk, 3, y, 7, Blocks.AIR.defaultBlockState());
                map.update(3, chunk.getMaxY(), 7, Blocks.STONE.defaultBlockState());
                map.update(3, chunk.getMaxY(), 7, Blocks.AIR.defaultBlockState());
                assertEquals(chunk.getMinY(), map.getFirstAvailable(3, 7));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Heightmap.Types.class)
    void airOpaquePredicateRetainsOriginalSearchSemantics(Heightmap.Types type) throws Exception {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
            LevelHeightAccessor.create(-1024, 2048), PalettedContainerFactory.create(RegistryHelper.registryAccess()), null);
        Heightmap map = new Heightmap(chunk, type);
        var field = Heightmap.class.getDeclaredField("isOpaque");
        field.setAccessible(true);
        field.set(map, (java.util.function.Predicate<BlockState>) BlockState::isAir);
        map.update(0, 32, 0, Blocks.AIR.defaultBlockState());
        map.update(0, 32, 0, Blocks.STONE.defaultBlockState());
        assertEquals(32, map.getFirstAvailable(0, 0), "An air-accepting predicate must inspect the empty section");
    }

    private static void write(ProtoChunk chunk, int x, int y, int z, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state);
    }

    private static int scan(ProtoChunk chunk, Heightmap.Types type, int x, int z) {
        for (int y = chunk.getMaxY(); y >= chunk.getMinY(); y--) {
            if (type.isOpaque().test(chunk.getBlockState(new BlockPos(x, y, z)))) return y + 1;
        }
        return chunk.getMinY();
    }
}
