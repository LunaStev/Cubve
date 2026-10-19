package io.github.lunastev.cubve.benchmark;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable benchmark fixture. Never include this plugin in a production server artifact. */
public final class CloneCommandProbe extends JavaPlugin {
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private ThreadMXBean allocationBean;
    private boolean busy;

    @Override
    public void onEnable() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof ThreadMXBean supported) || !supported.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This fixture requires per-thread allocated-byte counters");
        }
        this.allocationBean = supported;
        if (!supported.isThreadAllocatedMemoryEnabled()) {
            supported.setThreadAllocatedMemoryEnabled(true);
        }
        Objects.requireNonNull(this.getCommand("cubvecloneprobe")).setExecutor(this);
        this.getLogger().info("Disposable native-clone probe ready; setup/reset must run outside each measurement");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("This disposable benchmark fixture accepts console commands only.");
            return true;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Probe commands must arrive on the primary thread");
        }
        if (args.length < 11 || !LABEL.matcher(args[0]).matches()) {
            return false;
        }

        int repetitions;
        try {
            repetitions = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (repetitions < 1 || repetitions > 1_000) {
            return false;
        }
        if (this.busy) {
            throw new IllegalStateException("A clone probe is already queued or running");
        }

        String label = args[0];
        String nativeCommand = "minecraft:clone " + String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        this.busy = true;
        // A nested vanilla command may only enqueue work in its parent's execution context.
        // A scheduler task leaves that context; the callback below also verifies synchronous completion.
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                this.measure(label, repetitions, nativeCommand);
            } catch (RuntimeException | Error failure) {
                this.getLogger().severe("CUBVE_CLONE_PROBE_ERROR label=" + label + " " + failure);
                throw failure;
            } finally {
                this.busy = false;
            }
        });
        return true;
    }

    private void measure(String label, int repetitions, String nativeCommand) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Native clone execution must run on the primary thread");
        }
        MinecraftServer server = ((CraftServer) this.getServer()).getServer();
        Commands commands = server.getCommands();
        ResultSink result = new ResultSink();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput().withCallback(result);
        long threadId = Thread.currentThread().threadId();
        long[] allocatedBytes = new long[repetitions];
        long[] nanos = new long[repetitions];
        int[] affectedBlocks = new int[repetitions];

        // Initialize the counter path before taking measurements. Setup, wrapper construction,
        // result arrays, validation and JSON/log formatting are all outside the timed region.
        if (this.allocationBean.getThreadAllocatedBytes(threadId) < 0) {
            throw new IllegalStateException("Allocated-byte counter unavailable for server thread");
        }
        for (int iteration = 0; iteration < repetitions; iteration++) {
            result.reset();
            long beforeBytes = this.allocationBean.getThreadAllocatedBytes(threadId);
            long started = System.nanoTime();
            commands.performPrefixedCommand(source, nativeCommand);
            long elapsed = System.nanoTime() - started;
            long afterBytes = this.allocationBean.getThreadAllocatedBytes(threadId);

            if (result.calls != 1 || !result.success || result.value <= 0) {
                throw new IllegalStateException("Native clone did not complete successfully and synchronously: callbacks="
                    + result.calls + ", success=" + result.success + ", result=" + result.value);
            }
            if (afterBytes < beforeBytes) {
                throw new IllegalStateException("Allocated-byte counter was reset during measurement");
            }
            allocatedBytes[iteration] = afterBytes - beforeBytes;
            nanos[iteration] = elapsed;
            affectedBlocks[iteration] = result.value;
        }

        // Emit after every measured invocation has ended, so log formatting is not benchmark work.
        for (int iteration = 0; iteration < repetitions; iteration++) {
            this.getLogger().info("CUBVE_CLONE_PROBE {\"label\":\"" + label
                + "\",\"iteration\":" + iteration + ",\"dispatch\":true,\"success\":true,\"result\":"
                + affectedBlocks[iteration] + ",\"allocatedBytes\":" + allocatedBytes[iteration]
                + ",\"nanos\":" + nanos[iteration] + "}");
        }
        this.getLogger().info("CUBVE_CLONE_PROBE_DONE label=" + label + " repetitions=" + repetitions);
    }

    private static final class ResultSink implements CommandResultCallback {
        private int calls;
        private boolean success;
        private int value;

        private void reset() {
            this.calls = 0;
            this.success = false;
            this.value = 0;
        }

        @Override
        public void onResult(boolean success, int value) {
            this.calls++;
            this.success = success;
            this.value = value;
        }
    }
}
