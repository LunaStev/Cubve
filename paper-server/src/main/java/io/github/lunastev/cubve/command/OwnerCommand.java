package io.github.lunastev.cubve.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.lunastev.cubve.permission.OwnerAccess;
import io.github.lunastev.cubve.permission.OwnerRegistry;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;

public final class OwnerCommand {
    private OwnerCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("owner")
            .requires(source -> OwnerAccess.isConsole(source.getSender()))
            .then(Commands.literal("list").executes(context -> list(context.getSource().getSender())))
            .then(Commands.literal("add").then(Commands.argument("player-or-uuid", StringArgumentType.word())
                .executes(context -> change(context.getSource().getSender(), StringArgumentType.getString(context, "player-or-uuid"), true))))
            .then(Commands.literal("remove").then(Commands.argument("uuid", StringArgumentType.word())
                .executes(context -> change(context.getSource().getSender(), StringArgumentType.getString(context, "uuid"), false))))
            .executes(context -> list(context.getSource().getSender()));
    }

    private static int list(final CommandSender sender) {
        if (!OwnerAccess.isConsole(sender)) {
            return 0;
        }
        final CraftServer server = (CraftServer) sender.getServer();
        sender.sendMessage(Component.text("Cubve owners: " + server.cubveOwners().entries().size(), NamedTextColor.AQUA));
        for (OwnerRegistry.Entry entry : server.cubveOwners().entries()) {
            sender.sendMessage(Component.text(entry.name() + " — " + entry.id(), NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int change(final CommandSender sender, final String input, final boolean add) {
        if (!OwnerAccess.isConsole(sender)) {
            return 0;
        }
        final CraftServer server = (CraftServer) sender.getServer();
        final Player online = add ? server.getPlayerExact(input) : null;
        final UUID id;
        final String name;
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            try {
                id = UUID.fromString(input);
                if (!id.toString().equalsIgnoreCase(input)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                sender.sendMessage(Component.text("Use an online player's exact name or a complete UUID; remove requires the UUID shown by owner list.", NamedTextColor.RED));
                return 0;
            }
            name = id.toString();
        }
        try {
            final boolean changed = add ? server.cubveOwners().add(id, name) : server.cubveOwners().remove(id);
            if (!changed) {
                sender.sendMessage(Component.text(add ? "That UUID is already an owner." : "That UUID is not an owner.", NamedTextColor.YELLOW));
                return 0;
            }
            final net.minecraft.server.level.ServerPlayer player = server.getHandle().getPlayer(id);
            if (player != null) {
                server.getHandle().sendPlayerPermissionLevel(player);
            }
            final String message = (add ? "Added Cubve owner " : "Removed Cubve owner ") + name + " (" + id + ")";
            sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
            server.getLogger().info(message);
            return Command.SINGLE_SUCCESS;
        } catch (IOException exception) {
            sender.sendMessage(Component.text("Owner file could not be saved; ownership was not changed.", NamedTextColor.RED));
            server.getLogger().log(java.util.logging.Level.SEVERE, "Cannot save Cubve owners", exception);
            return 0;
        }
    }
}
