package io.github.lunastev.cubve.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.lunastev.cubve.chunk.ChunkPregenerator;
import io.github.lunastev.cubve.chunk.PregenerationJob;
import io.github.lunastev.cubve.permission.OwnerAccess;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.PaperCommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

public final class PregenCommand {
    private PregenCommand() { }

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pregen").requires(PregenCommand::allowed)
            .executes(PregenCommand::status)
            .then(Commands.literal("status").executes(PregenCommand::status))
            .then(Commands.literal("pause").executes(context -> change(context, "pause")))
            .then(Commands.literal("resume").executes(context -> change(context, "resume")))
            .then(Commands.literal("cancel").executes(context -> change(context, "cancel")))
            .then(Commands.literal("start")
                .then(Commands.argument("world", StringArgumentType.string())
                    .suggests((context, builder) -> {
                        for (World world : context.getSource().getSender().getServer().getWorlds()) {
                            String name = StringArgumentType.escapeIfRequired(world.getName());
                            if (name.startsWith(builder.getRemaining())) builder.suggest(name);
                        }
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("center-x", IntegerArgumentType.integer(-29_999_968, 29_999_968))
                        .then(Commands.argument("center-z", IntegerArgumentType.integer(-29_999_968, 29_999_968))
                            .then(Commands.argument("radius-blocks", IntegerArgumentType.integer(0, 100_000))
                                .executes(context -> start(context, 1))
                                .then(Commands.argument("chunks-per-second", IntegerArgumentType.integer(1, 4))
                                    .executes(context -> start(context, IntegerArgumentType.getInteger(context, "chunks-per-second")))))))));
    }

    static boolean allowed(CommandSourceStack source) {
        return source instanceof PaperCommandSourceStack paper
            ? OwnerAccess.canControl(paper.getHandle()) : OwnerAccess.canControl(source.getSender());
    }

    private static ChunkPregenerator service(CommandContext<CommandSourceStack> context) {
        return ((CraftServer) context.getSource().getSender().getServer()).cubvePregenerator();
    }

    private static int start(CommandContext<CommandSourceStack> context, int rate) {
        if (!allowed(context.getSource())) return 0;
        final CommandSender sender = context.getSource().getSender();
        final World world = sender.getServer().getWorld(StringArgumentType.getString(context, "world"));
        if (!(world instanceof CraftWorld craft)) {
            sender.sendMessage(Component.text("World is not loaded.", NamedTextColor.RED));
            return 0;
        }
        try {
            final PregenerationJob.Area area = PregenerationJob.Area.around(
                IntegerArgumentType.getInteger(context, "center-x"), IntegerArgumentType.getInteger(context, "center-z"),
                IntegerArgumentType.getInteger(context, "radius-blocks"));
            service(context).start(craft, area, rate);
            sender.sendMessage(Component.text("Pregeneration started: " + area.size() + " chunks, at most " + rate
                + "/second, one pending request. Use /pregen status, pause, resume or cancel.", NamedTextColor.GREEN));
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage(Component.text(exception.getMessage(), NamedTextColor.RED));
            return 0;
        }
    }

    private static int change(CommandContext<CommandSourceStack> context, String action) {
        if (!allowed(context.getSource())) return 0;
        final PregenerationJob job = service(context).job();
        if (job != null) {
            switch (action) {
                case "pause" -> job.pause();
                case "resume" -> job.resume();
                case "cancel" -> job.cancel();
                default -> throw new IllegalArgumentException(action);
            }
        }
        return status(context);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        if (!allowed(context.getSource())) return 0;
        final ChunkPregenerator service = service(context);
        final PregenerationJob job = service.job();
        context.getSource().getSender().sendMessage(Component.text(job == null ? "No pregeneration job. /pregen start <world> <center-x> <center-z> <radius-blocks> [chunks-per-second]"
            : "Pregeneration " + service.worldName() + ": " + job.state() + " " + job.completed() + "/" + job.total()
                + ", pending=" + job.inFlight() + " — " + job.reason(), NamedTextColor.AQUA));
        return Command.SINGLE_SUCCESS;
    }
}
