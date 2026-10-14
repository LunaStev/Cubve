package org.bukkit.craftbukkit;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.block.Biome;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class BiomeColumnOptimizationTest {
    @ParameterizedTest
    @CsvSource({"-64,320", "0,256", "-63,317", "-1,2", "7,8", "7,9", "0,0", "8,4"})
    void eachOriginalBiomeCellIsWrittenExactlyOnce(final int minHeight, final int maxHeight) {
        final CraftWorld world = mock(CraftWorld.class);
        when(world.getMinHeight()).thenReturn(minHeight);
        when(world.getMaxHeight()).thenReturn(maxHeight);
        doCallRealMethod().when(world).setBiome(5, 7, Biome.PLAINS);
        final Set<Integer> written = new HashSet<>();
        doAnswer(invocation -> {
            final int y = invocation.getArgument(1);
            assertTrue(y >= minHeight && y < maxHeight);
            assertTrue(written.add(y >> 2), "The same biome cell must not be written twice");
            return null;
        }).when(world).setBiome(eq(5), anyInt(), eq(7), same(Biome.PLAINS));
        world.setBiome(5, 7, Biome.PLAINS);
        final Set<Integer> expected = new HashSet<>();
        for (int y = minHeight; y < maxHeight; y++) {
            expected.add(y >> 2);
        }
        assertEquals(expected, written);
    }
}
