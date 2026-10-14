package com.destroystokyo.paper.network;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@AllFeatures
class ServerListPrivacyTest {
    private MinecraftServer server;
    private PluginManager plugins;
    private Connection connection;

    @BeforeEach
    void setUp() {
        this.server = mock(MinecraftServer.class);
        this.server.server = mock(CraftServer.class);
        this.plugins = mock(PluginManager.class);
        when(this.server.server.getPluginManager()).thenReturn(this.plugins);
        when(this.server.motd()).thenReturn(net.kyori.adventure.text.Component.text("Cubve"));
        when(this.server.getPlayerCount()).thenReturn(7);
        when(this.server.getMaxPlayers()).thenReturn(37);
        when(this.server.getServerModName()).thenReturn("Cubve");
        when(this.server.getServerVersion()).thenReturn("26.3");
        when(this.server.getStatus()).thenReturn(new ServerStatus(Component.literal("Cubve"),
            Optional.of(new ServerStatus.Players(37, 7, List.of(new NameAndId(UUID.randomUUID(), "PrivatePlayer")))),
            Optional.of(new ServerStatus.Version("Cubve 26.3", 1)), Optional.empty(), true));
        when(this.server.enforceSecureProfile()).thenReturn(true);
        this.connection = mock(Connection.class);
        when(this.connection.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
    }

    @Test
    void modernResponseOmitsCountsAndNamesEvenAfterPluginOverride() {
        doAnswer(invocation -> {
            PaperServerListPingEvent event = invocation.getArgument(0);
            assertTrue(event.shouldHidePlayers());
            event.setHidePlayers(false);
            event.motd(net.kyori.adventure.text.Component.text("Custom MOTD"));
            event.setVersion("Custom version");
            return null;
        }).when(this.plugins).callEvent(any(PaperServerListPingEvent.class));

        StandardPaperServerListPingEventImpl.processRequest(this.server, this.connection);
        var packet = ArgumentCaptor.forClass(ClientboundStatusResponsePacket.class);
        verify(this.connection).send(packet.capture());
        ServerStatus status = packet.getValue().status();
        assertTrue(status.players().isEmpty());
        assertEquals("Custom MOTD", status.description().getString());
        assertEquals("Custom version", status.version().orElseThrow().name());
        assertTrue(status.enforcesSecureChat());

        var buffer = Unpooled.buffer();
        try {
            ClientboundStatusResponsePacket.STREAM_CODEC.encode(buffer, packet.getValue());
            String json = new FriendlyByteBuf(buffer).readUtf(32767);
            assertFalse(com.google.gson.JsonParser.parseString(json).getAsJsonObject().has("players"));
            assertFalse(json.contains("PrivatePlayer"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void legacyResponseUsesUnknownCountsEvenAfterPluginOverride() {
        doAnswer(invocation -> {
            PaperServerListPingEvent event = invocation.getArgument(0);
            assertTrue(event.shouldHidePlayers());
            event.setHidePlayers(false);
            return null;
        }).when(this.plugins).callEvent(any(PaperServerListPingEvent.class));

        var event = PaperLegacyStatusClient.processRequest(this.server,
            new InetSocketAddress("127.0.0.1", 12345), 127, null);
        assertNotNull(event);
        assertTrue(event.shouldHidePlayers());
        assertEquals(-1, event.getNumPlayers());
        assertEquals(-1, event.getMaxPlayers());
    }

    @Test
    void cancelledModernPingSendsNoResponse() {
        doAnswer(invocation -> {
            invocation.<PaperServerListPingEvent>getArgument(0).setCancelled(true);
            return null;
        }).when(this.plugins).callEvent(any(PaperServerListPingEvent.class));
        StandardPaperServerListPingEventImpl.processRequest(this.server, this.connection);
        verify(this.connection).disconnect((Component) null);
        verify(this.connection, never()).send(any(ClientboundStatusResponsePacket.class));
    }

    @Test
    void cancelledLegacyPingSendsNoResponse() {
        doAnswer(invocation -> {
            invocation.<PaperServerListPingEvent>getArgument(0).setCancelled(true);
            return null;
        }).when(this.plugins).callEvent(any(PaperServerListPingEvent.class));
        assertNull(PaperLegacyStatusClient.processRequest(this.server,
            new InetSocketAddress("127.0.0.1", 12345), 127, null));
    }
}
