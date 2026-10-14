package org.bukkit.craftbukkit.inventory;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@AllFeatures
class InventoryInsertionOptimizationTest {
    private static final class CountingInventory extends CraftInventoryCustom {
        int snapshots;

        CountingInventory() {
            super(null, 54);
        }

        @Override
        public ItemStack[] getStorageContents() {
            this.snapshots++;
            return super.getStorageContents();
        }
    }

    @Test
    void fullInventoryReturnsOriginalLeftoverWithOneSnapshot() {
        final CountingInventory inventory = new CountingInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, ItemStack.of(Material.COBBLESTONE, 64));
        }
        final ItemStack incoming = ItemStack.of(Material.DIAMOND, 64);
        final Map<Integer, ItemStack> leftover = inventory.addItem(incoming);
        assertEquals(1, inventory.snapshots);
        assertSame(incoming, leftover.get(0));
        assertEquals(64, incoming.getAmount());
        assertEquals(Material.COBBLESTONE, inventory.getItem(0).getType());
    }

    @Test
    void oversizedInsertionRefreshesSnapshotAfterEveryWrite() {
        final CountingInventory inventory = new CountingInventory();
        final ItemStack incoming = ItemStack.of(Material.STONE, 130);
        assertTrue(inventory.addItem(incoming).isEmpty());
        assertEquals(3, inventory.snapshots);
        assertEquals(64, inventory.getItem(0).getAmount());
        assertEquals(64, inventory.getItem(1).getAmount());
        assertEquals(2, inventory.getItem(2).getAmount());
        // Preserve Bukkit's existing input-amount mutation behavior.
        assertEquals(2, incoming.getAmount());
    }

    @Test
    void partialStackIsFilledBeforeEmptySlots() {
        final CountingInventory inventory = new CountingInventory();
        inventory.setItem(4, ItemStack.of(Material.STONE, 32));
        final ItemStack incoming = ItemStack.of(Material.STONE, 80);
        assertTrue(inventory.addItem(incoming).isEmpty());
        assertEquals(64, inventory.getItem(4).getAmount());
        assertEquals(48, inventory.getItem(0).getAmount());
        assertEquals(48, incoming.getAmount());
        assertEquals(2, inventory.snapshots);
    }

    @Test
    void inventoryLimitAndDifferentMetadataRemainRespected() {
        final CountingInventory inventory = new CountingInventory();
        inventory.setMaxStackSize(16);
        final ItemStack existing = ItemStack.of(Material.STONE, 12);
        existing.editMeta(meta -> meta.setDisplayName("existing"));
        inventory.setItem(0, existing);
        final ItemStack incoming = ItemStack.of(Material.STONE, 37);
        incoming.editMeta(meta -> meta.setDisplayName("incoming"));
        assertTrue(inventory.addItem(incoming).isEmpty());
        assertEquals(12, inventory.getItem(0).getAmount());
        assertEquals(16, inventory.getItem(1).getAmount());
        assertEquals(16, inventory.getItem(2).getAmount());
        assertEquals(5, inventory.getItem(3).getAmount());
        assertEquals("incoming", inventory.getItem(3).getItemMeta().getDisplayName());
        assertEquals(5, incoming.getAmount());
    }

    @Test
    void laterInputsSeeEarlierInsertedStacks() {
        final CountingInventory inventory = new CountingInventory();
        assertTrue(inventory.addItem(ItemStack.of(Material.STONE, 32), ItemStack.of(Material.STONE, 32)).isEmpty());
        assertEquals(64, inventory.getItem(0).getAmount());
        assertNull(inventory.getItem(1));
        assertEquals(2, inventory.snapshots);
    }

    @Test
    void customEmptySlotSelectionIsNotBypassed() {
        final CraftInventoryCustom inventory = new CraftInventoryCustom(null, 9) {
            @Override
            public int firstEmpty() {
                return -1;
            }
        };
        final ItemStack incoming = ItemStack.of(Material.STONE, 32);
        assertSame(incoming, inventory.addItem(incoming).get(0));
        assertNull(inventory.getItem(0));
    }

    @Test
    void playerStorageDoesNotMergeIntoEquipment() {
        final var player = org.mockito.Mockito.mock(net.minecraft.world.entity.player.Player.class);
        final var equipment = new net.minecraft.world.entity.player.PlayerEquipment(player);
        final var container = new net.minecraft.world.entity.player.Inventory(player, equipment);
        equipment.set(net.minecraft.world.entity.EquipmentSlot.HEAD, CraftItemStack.asNMSCopy(ItemStack.of(Material.STONE, 32)));
        final CraftInventoryPlayer inventory = new CraftInventoryPlayer(container);
        assertTrue(inventory.addItem(ItemStack.of(Material.STONE, 16)).isEmpty());
        assertEquals(16, inventory.getItem(0).getAmount());
        assertEquals(32, equipment.get(net.minecraft.world.entity.EquipmentSlot.HEAD).getCount());
    }
}
