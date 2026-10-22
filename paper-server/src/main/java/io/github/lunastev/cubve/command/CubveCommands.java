package io.github.lunastev.cubve.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class CubveCommands {
    private CubveCommands() {
    }

    public static void registerPermissions() {
        registerPermission("cubve.command.server", PermissionDefault.TRUE);
        registerPermission("cubve.command.status", PermissionDefault.OP);
    }

    private static void registerPermission(final String name, final PermissionDefault defaults) {
        if (Bukkit.getPluginManager().getPermission(name) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(name, defaults));
        }
    }

    public static void registerCommands() {
        register(status(), "Shows server performance and resource usage");
        register(server(), "Shows the operating system and JVM memory usage");
        register(OwnerCommand.create(), "Manages Cubve owners from the console");
    }

    static LiteralArgumentBuilder<CommandSourceStack> server() {
        return Commands.literal("server")
            .requires(source -> source.getSender().hasPermission("cubve.command.server"))
            .executes(context -> showServer(context.getSource().getSender()));
    }

    static LiteralArgumentBuilder<CommandSourceStack> status() {
        return Commands.literal("status")
            .requires(source -> source.getSender().hasPermission("cubve.command.status"))
            .executes(context -> showStatus(context.getSource().getSender()));
    }

    private static void register(final LiteralArgumentBuilder<CommandSourceStack> command, final String description) {
        io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.registerWithFlagsInternal(
            null, "cubve", "Cubve", command.build(), description, List.of(), Set.of()
        );
    }

    private static int showServer(final CommandSender sender) {
        final Runtime runtime = Runtime.getRuntime();
        final long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long max = runtime.maxMemory() / (1024 * 1024);
        sender.sendMessage(Component.text("OS: " + System.getProperty("os.name") + " "
            + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")", NamedTextColor.AQUA));
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
            "JVM RAM: %d / %d MiB (used / maximum heap)", used, max
        ), NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(final CommandSender sender) {
        final double[] tps = Bukkit.getTPS();
        final Runtime runtime = Runtime.getRuntime();
        final long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        final long max = runtime.maxMemory() / (1024 * 1024);
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
            "TPS (1m / 5m / 15m): %.2f / %.2f / %.2f | MSPT: %.2f",
            Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2]), Bukkit.getAverageTickTime()
        ), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
            "Players: %d / %d | Worlds: %d | Heap: %d / %d MiB",
            Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), Bukkit.getWorlds().size(), used, max
        ), NamedTextColor.GRAY));
        return Command.SINGLE_SUCCESS;
    }
}
