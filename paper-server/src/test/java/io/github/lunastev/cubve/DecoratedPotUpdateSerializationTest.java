package io.github.lunastev.cubve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotPatterns;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

@AllFeatures
class DecoratedPotUpdateSerializationTest {
    private static final BlockPos POSITION = new BlockPos(7, 80, -9);

    @Test
    void customPotteryPatternSurvivesUpdatesWithoutExposingInventory() {
        DecoratedPotBlockEntity pot = decoratedPot();
        pot.setTheItem(new ItemStack(Items.DIAMOND, 17));
        CompoundTag saved = pot.saveWithoutMetadata(RegistryHelper.registryAccess());
        assertTrue(saved.contains("item"), "The server must retain the real pot contents");

        assertUpdateData(pot);

        assertTrue(pot.getTheItem().is(Items.DIAMOND));
        assertEquals(17, pot.getTheItem().getCount());
    }

    @Test
    void customPotteryPatternSurvivesUpdatesWithoutExposingLootTable() {
        DecoratedPotBlockEntity pot = decoratedPot();
        pot.setLootTable(BuiltInLootTables.DESERT_PYRAMID);
        pot.setLootTableSeed(42L);
        CompoundTag saved = pot.saveWithoutMetadata(RegistryHelper.registryAccess());
        assertEquals("minecraft:chests/desert_pyramid", saved.getStringOr("LootTable", ""));
        assertEquals(42L, saved.getLongOr("LootTableSeed", 0L));

        assertUpdateData(pot);

        assertEquals(BuiltInLootTables.DESERT_PYRAMID, pot.getLootTable());
        assertEquals(42L, pot.getLootTableSeed());
    }

    @Test
    void undecoratedPotHasNoClientUpdateData() {
        assertUpdateData(new DecoratedPotBlockEntity(POSITION, Blocks.DECORATED_POT.defaultBlockState()));
    }

    private static DecoratedPotBlockEntity decoratedPot() {
        var heart = RegistryHelper.registryAccess().lookupOrThrow(Registries.DECORATED_POT_PATTERN)
            .getOrThrow(DecoratedPotPatterns.HEART);
        // Override this sherd's ordinary angler pattern with a registry-backed component. A plain
        // sherd without this component patch does not exercise the missing serialization context.
        ItemStackTemplate sherd = new ItemStackTemplate(Items.ANGLER_POTTERY_SHERD,
            DataComponentPatch.builder().set(DataComponents.PROVIDES_POTTERY_PATTERN, heart).build());
        DecoratedPotBlockEntity pot = new DecoratedPotBlockEntity(POSITION, Blocks.DECORATED_POT.defaultBlockState());
        pot.decorations = new PotDecorations(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(sherd));
        return pot;
    }

    private static void assertUpdateData(DecoratedPotBlockEntity pot) {
        var registries = RegistryHelper.registryAccess();
        // Only the packet factory's registry access is mocked; the entity, codecs and packet are real.
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(registries);
        pot.setLevel(level);

        CompoundTag update = pot.getUpdateTag(registries);
        var packet = pot.getUpdatePacket();
        assertNotNull(packet);
        assertEquals(POSITION, packet.getPos());
        assertSame(BlockEntityTypes.DECORATED_POT, packet.getType());
        assertEquals(update, packet.getTag());

        for (CompoundTag tag : new CompoundTag[]{update, packet.getTag()}) {
            assertFalse(tag.contains("item"));
            assertFalse(tag.contains("LootTable"));
            assertFalse(tag.contains("LootTableSeed"));
            if (pot.getDecorations().equals(PotDecorations.EMPTY)) {
                assertTrue(tag.isEmpty());
            } else {
                assertEquals(Set.of("sherds"), tag.keySet());
                PotDecorations decoded = tag.read("sherds", PotDecorations.CODEC,
                    registries.createSerializationContext(NbtOps.INSTANCE)).orElseThrow();
                assertEquals(pot.getDecorations(), decoded);
                var pattern = decoded.front().orElseThrow().get(DataComponents.PROVIDES_POTTERY_PATTERN);
                assertNotNull(pattern);
                assertTrue(pattern.is(DecoratedPotPatterns.HEART));
            }
        }
    }
}
