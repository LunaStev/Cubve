package io.github.lunastev.cubve.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.lunastev.cubve.chunk.ChunkPregenerator;
import io.github.lunastev.cubve.permission.OwnerRegistry;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Normal
class PregenCommandTest {
    @TempDir Path directory;

    @Test
    void wildcardOperatorCannotControlPregenerationOrImpersonateOwner() throws Exception {
        CraftServer server = mock(CraftServer.class);
        OwnerRegistry owners = new OwnerRegistry(this.directory.resolve("owners.json"));
        UUID ownerId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        owners.add(ownerId, "Owner");
        when(server.cubveOwners()).thenReturn(owners);
        when(server.cubvePregenerator()).thenReturn(mock(ChunkPregenerator.class));
        MinecraftServer minecraft = mock(MinecraftServer.class);
        minecraft.server = server;
        Player operator = mock(Player.class);
        when(operator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(operator.getServer()).thenReturn(server);
        when(operator.isOp()).thenReturn(true);
        when(operator.hasPermission(anyString())).thenReturn(true);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getServer()).thenReturn(server);
        net.minecraft.commands.CommandSourceStack opSource = source(minecraft, operator);
        net.minecraft.commands.CommandSourceStack consoleSource = source(minecraft, console);
        assertFalse(PregenCommand.allowed(opSource));
        assertFalse(PregenCommand.allowed(opSource.withSource(consoleSource.source)));
        assertTrue(PregenCommand.allowed(consoleSource));
        when(operator.getUniqueId()).thenReturn(ownerId);
        assertTrue(PregenCommand.allowed(opSource));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(PregenCommand.create());
        assertEquals(1, dispatcher.execute("pregen status", consoleSource));
        assertEquals(1, dispatcher.execute("pregen status", opSource));
        owners.remove(ownerId);
        assertFalse(PregenCommand.allowed(opSource));
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
            () -> dispatcher.execute("pregen status", opSource));
    }

    private static net.minecraft.commands.CommandSourceStack source(MinecraftServer server, CommandSender sender) {
        CommandSource source = mock(CommandSource.class);
        when(source.getBukkitSender(any())).thenReturn(sender);
        return new net.minecraft.commands.CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, mock(ServerLevel.class),
            LevelBasedPermissionSet.OWNER, Component.literal("fixture"), server);
    }
}
