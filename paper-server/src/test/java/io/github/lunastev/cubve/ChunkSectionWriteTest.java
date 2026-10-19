package io.github.lunastev.cubve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.moonrise.common.list.ShortList;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers section counters and Moonrise tracking through actual checked/unchecked palette writes. */
@AllFeatures
class ChunkSectionWriteTest {
    private static final int CELL_COUNT = 16 * 16 * 16;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void repeatedIdentityWritesPreserveEveryCounterAndTickingPosition(boolean checked) throws ReflectiveOperationException {
        for (BlockState state : representativeStates()) {
            LevelChunkSection section = createSection();
            BlockState[] expected = emptyStates();
            int position = 1 | (3 << 4) | (2 << 8);
            assertSame(Blocks.AIR.defaultBlockState(), section.setBlockState(1, 2, 3, state, checked));
            expected[position] = state;
            assertContentsAndCounts(section, expected);
            Snapshot before = snapshot(section);
            for (int repeat = 0; repeat < 64; repeat++) {
                assertSame(state, section.setBlockState(1, 2, 3, state, checked));
                assertSame(Blocks.AIR.defaultBlockState(), section.setBlockState(15, 15, 15, Blocks.AIR.defaultBlockState(), checked));
            }
            assertEquals(before, snapshot(section), "Identity writes must retain exact counts and ticking-list order: " + state);
            assertContentsAndCounts(section, expected);
            assertSame(state, section.setBlockState(1, 2, 3, Blocks.AIR.defaultBlockState(), checked));
            expected[position] = Blocks.AIR.defaultBlockState();
            assertContentsAndCounts(section, expected);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mixedChangesAndRepeatedWritesKeepFluidTickingAndCollisionTrackingConsistent(boolean checked) throws ReflectiveOperationException {
        LevelChunkSection section = createSection();
        BlockState[] expected = emptyStates();
        BlockState[] states = representativeStates();
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < CELL_COUNT; i++) {
                BlockState next = (i & 3) == 0 && round > 0 ? expected[i] : states[(i + round) % states.length];
                assertSame(expected[i], write(section, i, next, checked));
                expected[i] = next;
            }
            assertContentsAndCounts(section, expected);
            Snapshot before = snapshot(section);
            for (int i = CELL_COUNT - 1; i >= 0; i--) {
                assertSame(expected[i], write(section, i, expected[i], checked));
            }
            assertEquals(before, snapshot(section));
        }
        for (int i = 0; i < CELL_COUNT; i++) {
            assertSame(expected[i], write(section, i, Blocks.AIR.defaultBlockState(), checked));
        }
        assertContentsAndCounts(section, emptyStates());
        assertFalse(section.isRandomlyTicking());
        assertFalse(section.moonrise$hasSpecialCollidingBlocks());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void identityWritesAfterPaletteGrowthPreserveAllCellsAndTracking(boolean checked) throws ReflectiveOperationException {
        LevelChunkSection section = createSection();
        BlockState[] expected = emptyStates();
        // More than 256 distinct states forces the section beyond its small local palette.
        assertTrue(Block.BLOCK_STATE_REGISTRY.size() > 300);
        for (int i = 0; i < CELL_COUNT; i++) {
            BlockState next = Block.BLOCK_STATE_REGISTRY.byId(i % 300);
            assertSame(expected[i], write(section, i, next, checked));
            expected[i] = next;
        }
        assertContentsAndCounts(section, expected);
        Snapshot before = snapshot(section);
        for (int repeat = 0; repeat < 3; repeat++) {
            for (int i = 0; i < CELL_COUNT; i++) {
                assertSame(expected[i], write(section, i, expected[i], checked));
            }
            assertEquals(before, snapshot(section));
            assertContentsAndCounts(section, expected);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void copiedSectionIdentityWritesPreserveExistingTrackingAndIndependentPalette(boolean checked) throws ReflectiveOperationException {
        LevelChunkSection source = trackedSection();
        LevelChunkSection copy = source.copy();
        // Copies carry the vanilla counters but do not copy all Moonrise tracking fields.
        // Preserve their existing state; a serialization copy is not a live server section.
        assertIdentityWritesPreserveSnapshot(source, copy, checked);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void packetReadSectionIdentityWritesPreserveExistingTrackingAndIndependentPalette(boolean checked) throws ReflectiveOperationException {
        LevelChunkSection source = trackedSection();
        LevelChunkSection received = createSection();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            source.write(buffer, null, 0);
            received.read(buffer);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        // read() marks a client section and uses a collision sentinel, without populating its
        // server random-tick list. Identity writes must preserve that existing tracking state.
        assertIdentityWritesPreserveSnapshot(source, received, checked);
    }

    private static LevelChunkSection trackedSection() {
        LevelChunkSection source = createSection();
        write(source, 0, Blocks.OAK_LEAVES.defaultBlockState(), true);
        write(source, 1, Blocks.WATER.defaultBlockState(), true);
        write(source, 2, Blocks.LAVA.defaultBlockState(), true);
        write(source, 3, Blocks.MOVING_PISTON.defaultBlockState(), true);
        return source;
    }

    private static void assertIdentityWritesPreserveSnapshot(LevelChunkSection source, LevelChunkSection section, boolean checked) throws ReflectiveOperationException {
        Snapshot sourceBefore = snapshot(source);
        Snapshot sectionBefore = snapshot(section);
        for (int repeat = 0; repeat < 3; repeat++) {
            for (int i = 0; i < CELL_COUNT; i++) {
                BlockState expected = source.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15);
                assertSame(expected, section.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15));
                assertSame(expected, write(section, i, expected, checked));
            }
            assertEquals(sectionBefore, snapshot(section));
        }
        // Use an ordinary solid at a previously empty cell so that this independence check
        // does not impose live-server tracking invariants on serialization/client sections.
        assertSame(Blocks.AIR.defaultBlockState(), write(section, CELL_COUNT - 1, Blocks.STONE.defaultBlockState(), checked));
        assertSame(Blocks.AIR.defaultBlockState(), source.getBlockState(15, 15, 15));
        assertSame(Blocks.STONE.defaultBlockState(), write(section, CELL_COUNT - 1, Blocks.AIR.defaultBlockState(), checked));
        assertEquals(sectionBefore, snapshot(section));
        assertEquals(sourceBefore, snapshot(source));
    }

    private static LevelChunkSection createSection() {
        return new LevelChunkSection(PalettedContainerFactory.create(RegistryHelper.registryAccess()), null, null, 0);
    }

    private static BlockState[] emptyStates() {
        BlockState[] states = new BlockState[CELL_COUNT];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return states;
    }

    private static BlockState[] representativeStates() {
        return new BlockState[]{
            Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
            Blocks.MOVING_PISTON.defaultBlockState()
        };
    }

    private static BlockState write(LevelChunkSection section, int index, BlockState state, boolean checked) {
        return section.setBlockState(index & 15, index >>> 8, (index >>> 4) & 15, state, checked);
    }

    private static void assertContentsAndCounts(LevelChunkSection section, BlockState[] expected) throws ReflectiveOperationException {
        long nonEmpty = Arrays.stream(expected).filter(state -> !state.isAir()).count();
        long fluids = Arrays.stream(expected).filter(state -> !state.getFluidState().isEmpty()).count();
        long tickingBlocks = Arrays.stream(expected).filter(BlockState::isRandomlyTicking).count();
        long tickingFluids = Arrays.stream(expected).filter(state -> state.getFluidState().isRandomlyTicking()).count();
        long special = Arrays.stream(expected).filter(CollisionUtil::isSpecialCollidingBlock).count();
        assertEquals(nonEmpty, count(section, "nonEmptyBlockCount"));
        assertEquals(fluids, count(section, "fluidCount"));
        assertEquals(tickingBlocks, count(section, "tickingBlockCount"));
        assertEquals(tickingFluids, count(section, "tickingFluidCount"));
        assertEquals(special, count(section, "specialCollidingBlocks"));
        assertEquals(nonEmpty == 0, section.hasOnlyAir());
        assertEquals(fluids > 0, section.hasFluid());
        assertEquals(tickingBlocks > 0, section.isRandomlyTickingBlocks());
        assertEquals(tickingFluids > 0, section.isRandomlyTickingFluids());
        assertEquals(special > 0, section.moonrise$hasSpecialCollidingBlocks());
        BitSet expectedTicking = new BitSet(CELL_COUNT);
        for (int i = 0; i < CELL_COUNT; i++) {
            assertSame(expected[i], section.getBlockState(i & 15, i >>> 8, (i >>> 4) & 15), "Cell " + i);
            assertSame(expected[i].getFluidState(), section.getFluidState(i & 15, i >>> 8, (i >>> 4) & 15), "Fluid cell " + i);
            if (expected[i].isRandomlyTicking()) {
                expectedTicking.set(i);
            }
        }
        ShortList actual = section.moonrise$getTickingBlockList();
        BitSet actualTicking = new BitSet(CELL_COUNT);
        for (int i = 0; i < actual.size(); i++) {
            int position = Short.toUnsignedInt(actual.getRaw(i));
            assertTrue(position < CELL_COUNT, "Ticking position must be section-local");
            assertFalse(actualTicking.get(position), "A ticking position must not be duplicated");
            actualTicking.set(position);
        }
        assertEquals(expectedTicking, actualTicking);
        assertEquals(tickingBlocks, actual.size());
    }

    private static short count(LevelChunkSection section, String name) throws ReflectiveOperationException {
        Field field = LevelChunkSection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getShort(section);
    }

    private static Snapshot snapshot(LevelChunkSection section) throws ReflectiveOperationException {
        ShortList ticking = section.moonrise$getTickingBlockList();
        List<Short> positions = new ArrayList<>(ticking.size());
        for (int i = 0; i < ticking.size(); i++) {
            positions.add(ticking.getRaw(i));
        }
        return new Snapshot(count(section, "nonEmptyBlockCount"), count(section, "fluidCount"),
            count(section, "tickingBlockCount"), count(section, "tickingFluidCount"),
            count(section, "specialCollidingBlocks"), List.copyOf(positions));
    }

    private record Snapshot(short nonEmpty, short fluids, short tickingBlocks, short tickingFluids,
                            short specialColliding, List<Short> tickingPositions) {
    }
}
