package io.github.lunastev.cubve.benchmark;

import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventoryCustom;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class InventoryBenchmark {
    private CraftInventoryCustom inventory;
    private ItemStack incoming;

    @Setup(Level.Trial)
    public void setup() {
        new AllFeaturesExtension().init(null);
        this.inventory = new CraftInventoryCustom(null, 54);
        for (int slot = 0; slot < 54; slot++) {
            this.inventory.setItem(slot, ItemStack.of(Material.COBBLESTONE, 64));
        }
        this.incoming = ItemStack.of(Material.DIAMOND, 64);
    }

    @Benchmark
    public Object addToFullInventory() {
        // Stable input: no slot or item amount changes when a full inventory rejects a different item.
        return this.inventory.addItem(this.incoming);
    }
}
