package io.github.lunastev.cubve.fixture;

import com.google.gson.Gson;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Separate, disposable test plugin; never included in the server artifact. */
public final class HeightProbe extends JavaPlugin {
    private final Gson gson = new Gson();
    private ThreadMXBean allocations;

    @Override public void onEnable() {
        this.allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        this.allocations.setThreadAllocatedMemoryEnabled(true);
        getCommand("cubveheightprobe").setExecutor(this);
        getLogger().info("Height probe ready; WorldEdit=" + getServer().getPluginManager().getPlugin("WorldEdit").getPluginMeta().getVersion());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return false;
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                World world = Bukkit.getWorld("world");
                require(world.getMinHeight() == -1024 && world.getMaxHeight() == 1024, "actual datapack height");
                switch (args[0]) {
                    case "setup" -> edit(world, Integer.parseInt(args[1]), true);
                    case "remove" -> {
                        int y = Integer.parseInt(args[2]);
                        for (int x = 1024; x < 1040; x++) for (int z = 1024; z < 1040; z++) {
                            require(world.getBlockAt(x, y, z).getType() == Material.STONE, "setup plane");
                        }
                        long bytes = this.allocations.getCurrentThreadAllocatedBytes();
                        long start = System.nanoTime();
                        edit(world, y, false);
                        long nanos = System.nanoTime() - start;
                        bytes = this.allocations.getCurrentThreadAllocatedBytes() - bytes;
                        for (int x = 1024; x < 1040; x++) for (int z = 1024; z < 1040; z++) {
                            require(world.getBlockAt(x, y, z).getType().isAir(), "WorldEdit removed plane");
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("label", args[1]); result.put("y", y); result.put("blocks", 256);
                        result.put("nanos", nanos); result.put("allocatedBytes", bytes);
                        getLogger().info("CUBVE_HEIGHT_METRIC " + this.gson.toJson(result));
                    }
                    case "regression" -> regression(world);
                    case "verify" -> verify(world);
                    case "maps" -> verifyMaps(world);
                    case "ready" -> {
                        var level = ((CraftWorld) world).getHandle();
                        int pending = 0;
                        for (int x = 63; x <= 65; x++) for (int z = 63; z <= 65; z++) {
                            LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(x, z);
                            if (chunk == null || !chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING)) {
                                pending++;
                            } else {
                                for (var offsets : chunk.getPostProcessing()) {
                                    if (offsets != null && !offsets.isEmpty()) { pending++; break; }
                                }
                            }
                        }
                        getLogger().info("CUBVE_HEIGHT_METRIC " + this.gson.toJson(Map.of("pending", pending)));
                    }
                    default -> throw new IllegalArgumentException("unknown probe operation");
                }
                getLogger().info("CUBVE_HEIGHT_PASS " + String.join(" ", args));
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "CUBVE_HEIGHT_FAIL " + String.join(" ", args), failure);
            }
        });
        return true;
    }

    private static EditSession session(World world) { return WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world)); }

    private static void edit(World world, int y, boolean stone) throws Exception {
        try (EditSession edit = session(world)) {
            int changed = edit.setBlocks(new CuboidRegion(BlockVector3.at(1024, y, 1024), BlockVector3.at(1039, y, 1039)),
                (stone ? BlockTypes.STONE : BlockTypes.AIR).getDefaultState());
            require(changed == 256, "WorldEdit affected-block count: " + changed);
        }
    }

    private static void regression(World world) throws Exception {
        BlockVector3 from = BlockVector3.at(1025, 990, 1025);
        BlockVector3 to = BlockVector3.at(1028, 990, 1025);
        world.getBlockAt(1025, 990, 1025).setType(Material.CHEST);
        Chest source = (Chest) world.getBlockAt(1025, 990, 1025).getState();
        source.getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 13));
        EditSession history = session(world);
        try (history) { history.setBlock(to, history.getFullBlock(from)); }
        checkChest(world, 1028);
        try (EditSession undo = session(world)) { history.undo(undo); }
        require(world.getBlockAt(1028, 990, 1025).getType().isAir(), "WorldEdit undo");
        try (EditSession redo = session(world)) { history.redo(redo); }
        checkChest(world, 1028);
        // Enclose the low-altitude fluid so startup ticks cannot alter the expected saved volume.
        try (EditSession edit = session(world)) {
            edit.setBlocks(new CuboidRegion(BlockVector3.at(1024, -902, 1026), BlockVector3.at(1026, -900, 1028)),
                BlockTypes.GLASS.getDefaultState());
            edit.setBlock(BlockVector3.at(1025, -901, 1027), BlockTypes.WATER.getDefaultState());
            edit.setBlock(BlockVector3.at(1030, 1002, 1030), BlockTypes.SEA_LANTERN.getDefaultState());
        }
        verifyMaps(world);
    }

    private static void checkChest(World world, int x) {
        require(world.getBlockAt(x, 990, 1025).getState() instanceof Chest, "copied chest");
        Chest chest = (Chest) world.getBlockAt(x, 990, 1025).getState();
        ItemStack item = chest.getBlockInventory().getItem(0);
        require(item != null && item.getType() == Material.DIAMOND && item.getAmount() == 13, "copied chest inventory");
    }

    private static void verify(World world) {
        checkChest(world, 1025); checkChest(world, 1028);
        require(world.getBlockAt(1025, -901, 1027).getType() == Material.WATER, "saved low-altitude fluid");
        require(world.getBlockAt(1030, 1002, 1030).getType() == Material.SEA_LANTERN, "saved high-altitude light source");
        require(world.getBlockAt(1030, 1002, 1031).getLightFromBlocks() == 14, "adjacent block light");
        verifyMaps(world);
        verifyPacket(world);
    }

    private static void verifyPacket(World world) {
        var level = ((CraftWorld) world).getHandle();
        LevelChunk chunk = level.getChunk(64, 64);
        var packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
            chunk, level.getChunkSource().getLightEngine(), null, null, false);
        var buffer = packet.chunkData().getReadBuffer();
        int compared = 0;
        try {
            for (var expected : chunk.getSections()) {
                var decoded = new net.minecraft.world.level.chunk.LevelChunkSection(level.palettedContainerFactory(), null, null, 0);
                decoded.read(buffer);
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    require(decoded.getBlockState(x, y, z) == expected.getBlockState(x, y, z), "packet block state");
                }
                for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) {
                    require(decoded.getNoiseBiome(x, y, z) == expected.getNoiseBiome(x, y, z), "packet biome");
                }
                compared++;
            }
            require(compared == 128 && !buffer.isReadable(), "all 128 packet sections decoded");
        } finally { buffer.release(); }
    }

    private static void verifyMaps(World world) {
        LevelChunk chunk = ((CraftWorld) world).getHandle().getChunk(64, 64);
        for (var entry : chunk.getHeightmaps()) {
            Heightmap.Types type = entry.getKey();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int expected = chunk.getMinY();
                for (int y = chunk.getMaxY(); y >= chunk.getMinY(); y--) {
                    if (type.isOpaque().test(chunk.getBlockState(new BlockPos(x, y, z)))) { expected = y + 1; break; }
                }
                require(entry.getValue().getFirstAvailable(x, z) == expected, "actual heightmap " + type + " " + x + "," + z);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
