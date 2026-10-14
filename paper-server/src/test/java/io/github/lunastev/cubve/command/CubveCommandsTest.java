package io.github.lunastev.cubve.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Normal
class CubveCommandsTest {
    @Test
    void serverInformationDoesNotGrantAccessToStatus() {
        final CommandSender sender = mock(CommandSender.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        when(sender.hasPermission("cubve.command.server")).thenReturn(true);

        final var server = CubveCommands.server().build();
        assertTrue(server.canUse(source));
        assertTrue(server.getChildren().isEmpty());
        assertFalse(CubveCommands.status().build().canUse(source));

        when(sender.hasPermission("cubve.command.status")).thenReturn(true);
        assertTrue(CubveCommands.status().build().canUse(source));
    }

    @Test
    void serverPermissionCanBeRevoked() {
        final CommandSender sender = mock(CommandSender.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        when(sender.hasPermission("cubve.command.status")).thenReturn(true);
        assertFalse(CubveCommands.server().build().canUse(source));
        assertTrue(CubveCommands.status().build().canUse(source));
    }
    @Test
    void serverReportsOnlyOperatingSystemAndJvmMemory() throws Exception {
        final CommandSender sender = mock(CommandSender.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        when(sender.hasPermission("cubve.command.server")).thenReturn(true);
        final var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
        dispatcher.register(CubveCommands.server());
        assertEquals(1, dispatcher.execute("server", source));
        final var messages = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(sender, times(2)).sendMessage(messages.capture());
        final var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        assertTrue(plain.serialize(messages.getAllValues().get(0)).contains(System.getProperty("os.name")));
        assertTrue(plain.serialize(messages.getAllValues().get(1)).startsWith("JVM RAM:"));
        assertTrue(CubveCommands.server().build().getChildren().isEmpty());
    }

}
