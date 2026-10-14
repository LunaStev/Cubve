package org.bukkit.craftbukkit.inventory;

import io.papermc.paper.configuration.WorldConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@AllFeatures
class HopperContainerLookupOptimizationTest {
    private static final BlockPos POS = new BlockPos(15, 120, 15);

    private static Level level(final boolean canLoadChunks, final boolean loaded) throws Exception {
        final Level level = mock(Level.class);
        final SpigotWorldConfig config = mock(SpigotWorldConfig.class);
        config.hopperCanLoadChunks = canLoadChunks;
        final Field field = Level.class.getDeclaredField("spigotConfig");
        field.setAccessible(true);
        field.set(level, config);
        when(level.hasChunkAt(POS)).thenReturn(loaded);
        return level;
    }

    private static Container lookup(final Level level, final BlockState state) throws Exception {
        final Method method = HopperBlockEntity.class.getDeclaredMethod("getBlockContainer", Level.class, BlockPos.class, BlockState.class);
        method.setAccessible(true);
        return (Container) method.invoke(null, level, POS, state);
    }

    @Test
    void ordinaryBlocksDoNotProbeChunksOrBlockEntities() throws Exception {
        final Level level = level(false, true);
        for (final BlockState state : List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(), Blocks.CAULDRON.defaultBlockState(), Blocks.REDSTONE_BLOCK.defaultBlockState())) {
            assertNull(lookup(level, state));
        }
        verify(level, never()).hasChunkAt(any());
        verify(level, never()).getBlockEntity(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realContainerRetainsLoadedChunkGuard(final boolean loaded) throws Exception {
        final Level level = level(false, loaded);
        final HopperBlockEntity hopper = new HopperBlockEntity(POS, Blocks.HOPPER.defaultBlockState());
        when(level.getBlockEntity(POS)).thenReturn(hopper);
        assertSame(loaded ? hopper : null, lookup(level, Blocks.HOPPER.defaultBlockState()));
        verify(level).hasChunkAt(POS);
        verify(level, times(loaded ? 1 : 0)).getBlockEntity(POS);
    }

    @Test
    void enablingChunkLoadsRetainsBlockEntityLookupWithoutLoadedGuard() throws Exception {
        final Level level = level(true, false);
        final HopperBlockEntity hopper = new HopperBlockEntity(POS, Blocks.HOPPER.defaultBlockState());
        when(level.getBlockEntity(POS)).thenReturn(hopper);
        assertSame(hopper, lookup(level, Blocks.HOPPER.defaultBlockState()));
        verify(level, never()).hasChunkAt(any());
    }

    @Test
    void replacementBlockEntityIsSeenOnTheNextLookup() throws Exception {
        final Level level = level(false, true);
        final HopperBlockEntity first = new HopperBlockEntity(POS, Blocks.HOPPER.defaultBlockState());
        final HopperBlockEntity replacement = new HopperBlockEntity(POS, Blocks.HOPPER.defaultBlockState());
        when(level.getBlockEntity(POS)).thenReturn(first, replacement, null);
        assertSame(first, lookup(level, Blocks.HOPPER.defaultBlockState()));
        assertSame(replacement, lookup(level, Blocks.HOPPER.defaultBlockState()));
        assertNull(lookup(level, Blocks.HOPPER.defaultBlockState()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void worldlyContainerWithoutBlockEntityIsStillRecognized(final boolean loaded) throws Exception {
        final Level level = level(false, loaded);
        final Container container = lookup(level, Blocks.COMPOSTER.defaultBlockState());
        if (loaded) {
            assertNotNull(container);
        } else {
            assertNull(container);
        }
        verify(level).hasChunkAt(POS);
        verify(level, never()).getBlockEntity(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void skippingBlockLookupDoesNotSkipMinecartContainerSearch(final boolean ignoreOccludingBlocks) throws Exception {
        final Level level = level(false, true);
        when(level.getBlockState(POS)).thenReturn(Blocks.STONE.defaultBlockState());
        final WorldConfiguration config = mock(WorldConfiguration.class);
        config.hopper = config.new Hopper();
        config.hopper.ignoreOccludingBlocks = ignoreOccludingBlocks;
        when(level.paperConfig()).thenReturn(config);
        final MinecartChest minecart = mock(MinecartChest.class);
        when(level.getEntitiesOfClass(eq((Class) Container.class), any(), any())).thenReturn((List) List.of(minecart));
        when(level.getRandom()).thenReturn(RandomSource.create(1));
        assertSame(ignoreOccludingBlocks ? null : minecart, HopperBlockEntity.getContainerAt(level, POS));
        verify(level, never()).hasChunkAt(any());
        verify(level, times(ignoreOccludingBlocks ? 0 : 1)).getEntitiesOfClass(eq((Class) Container.class), any(), any());
    }
}
