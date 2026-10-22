package io.github.lunastev.cubve.permission;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;

public final class OwnerAccess {
    private static final Set<String> CONTROL_PERMISSIONS = Set.of(
        "minecraft.command.stop", "minecraft.command.op", "minecraft.command.deop", "minecraft.command.reload",
        "minecraft.command.save-off", "minecraft.command.save-on", "minecraft.command.datapack", "minecraft.command.schedule"
    );
    private static final String CONTROL_MESSAGE = "Only a Cubve owner or the console can control the server.";
    private static final SimpleCommandExceptionType CONTROL_DENIED = new SimpleCommandExceptionType(
        Component.literal(CONTROL_MESSAGE)
    );
    private static final SimpleCommandExceptionType PROTECTED = new SimpleCommandExceptionType(
        Component.literal("Cubve owners are protected. Remove ownership from the console first.")
    );

    private OwnerAccess() {
    }

    public static boolean isOwner(final CraftServer server, final UUID id) {
        return server != null && server.cubveOwners() != null && server.cubveOwners().contains(id);
    }

    public static CommandSender caller(final CommandSender sender) {
        CommandSender current = sender;
        // Proxies must never gain their callee's owner/console status.
        for (int depth = 0; current instanceof ProxiedCommandSender proxy && depth < 32; depth++) {
            current = proxy.getCaller();
        }
        return current;
    }

    public static boolean isConsole(final CommandSender sender) {
        final CommandSender caller = caller(sender);
        return caller instanceof ConsoleCommandSender || caller instanceof RemoteConsoleCommandSender;
    }

    public static boolean canControl(final CommandSender sender) {
        final CommandSender caller = caller(sender);
        if (isConsole(caller) || caller instanceof io.papermc.paper.commands.FeedbackForwardingSender) {
            return true;
        }
        return caller instanceof Player player && caller.getServer() instanceof CraftServer server && isOwner(server, player.getUniqueId());
    }

    public static boolean canControl(final CommandSourceStack source) {
        return source.getServer() == null || canControl(source.cubveOriginalSender());
    }

    public static boolean allowsPermission(final CommandSourceStack source, final String permission) {
        return !CONTROL_PERMISSIONS.contains(permission) || canControl(source);
    }

    public static void requireControl(final CommandSourceStack source) throws CommandSyntaxException {
        if (!canControl(source)) {
            throw CONTROL_DENIED.create();
        }
    }

    public static boolean checkControl(final CommandSender sender) {
        if (canControl(sender)) {
            return true;
        }
        sender.sendMessage(net.kyori.adventure.text.Component.text(CONTROL_MESSAGE, net.kyori.adventure.text.format.NamedTextColor.RED));
        return false;
    }

    public static void requireUnprotected(final CraftServer server, final UUID id) throws CommandSyntaxException {
        if (isOwner(server, id)) {
            throw PROTECTED.create();
        }
    }

    public static void checkModerationTarget(final CraftServer server, final UUID id) {
        if (isOwner(server, id)) {
            throw new IllegalArgumentException("Cubve owners are protected; remove ownership from the console first");
        }
    }
}
