package io.github.lunastev.cubve.benchmark;

import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.support.extension.AllFeaturesExtension;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ItemFlagsBenchmark {
    @Param({"empty", "attributes"})
    public String flags;
    private ItemMeta meta;

    @Setup(Level.Trial)
    public void setup() {
        new AllFeaturesExtension().init(null);
        this.meta = ItemStack.of(Material.DIAMOND_HOE).getItemMeta();
        if (this.flags.equals("attributes")) {
            this.meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
    }

    @Benchmark
    public Object itemFlags() {
        return this.meta.getItemFlags();
    }
}
