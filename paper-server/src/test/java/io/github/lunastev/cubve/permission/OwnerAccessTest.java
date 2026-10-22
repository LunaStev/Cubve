package io.github.lunastev.cubve.permission;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lunastev.cubve.command.OwnerCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.BanIpCommands;
import net.minecraft.server.commands.BanPlayerCommands;
import net.minecraft.server.commands.KickCommand;
import net.minecraft.server.commands.OpCommand;
import net.minecraft.server.commands.StopCommand;
import net.minecraft.server.commands.TransferCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.notifications.NotificationService;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Normal
class OwnerAccessTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @TempDir Path directory;
    private OwnerRegistry registry;
    private CraftServer server;
    private MinecraftServer minecraft;
    private Player owner;
    private Player operator;

    @BeforeEach
    void setup() throws Exception {
        this.registry = new OwnerRegistry(this.directory.resolve("owners.json"));
        this.registry.add(OWNER, "Owner");
        this.server = mock(CraftServer.class);
        when(this.server.cubveOwners()).thenReturn(this.registry);
        this.minecraft = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        this.minecraft.server = this.server;
        this.owner = player(OWNER, "Owner");
        this.operator = player(OPERATOR, "Operator");
    }

    private Player player(final UUID id, final String name) {
        final Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getServer()).thenReturn(this.server);
        when(player.isOp()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        return player;
    }

    private CommandSourceStack source(final CommandSender sender) {
        final CommandSource source = mock(CommandSource.class);
        when(source.getBukkitSender(any())).thenReturn(sender);
        return new CommandSourceStack(source, Vec3.ZERO, Vec2.ZERO, mock(ServerLevel.class),
            LevelBasedPermissionSet.OWNER, Component.literal("test"), this.minecraft);
    }

    @Test
    void ownershipIsIndependentOfOpAndWildcardPermissions() throws Exception {
        assertFalse(OwnerAccess.canControl(this.operator));
        assertTrue(OwnerAccess.canControl(this.owner));
        when(this.owner.isOp()).thenReturn(false);
        assertTrue(OwnerAccess.canControl(this.owner));
        this.registry.remove(OWNER);
        assertFalse(OwnerAccess.canControl(this.owner));
    }

    @Test
    void consolesAreTrustedAndProxyUsesCallerRatherThanCallee() {
        assertTrue(OwnerAccess.canControl(mock(ConsoleCommandSender.class)));
        assertTrue(OwnerAccess.canControl(mock(RemoteConsoleCommandSender.class)));
        assertFalse(OwnerAccess.canControl(mock(org.bukkit.command.BlockCommandSender.class)));
        final ProxiedCommandSender proxy = mock(ProxiedCommandSender.class);
        when(proxy.getCaller()).thenReturn(this.operator);
        when(proxy.getCallee()).thenReturn(this.owner);
        assertFalse(OwnerAccess.canControl(proxy));
        when(proxy.getCallee()).thenReturn(mock(ConsoleCommandSender.class));
        assertFalse(OwnerAccess.canControl(proxy));
        when(proxy.getCaller()).thenReturn(this.owner);
        when(proxy.getCallee()).thenReturn(this.operator);
        assertTrue(OwnerAccess.canControl(proxy));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "op", "deop", "reload", "save-off", "save-on", "datapack", "schedule"})
    void vanillaLevelFourAndBukkitPermissionsCannotBypassOwnerControls(final String command) {
        assertFalse(source(this.operator).hasPermission(Permissions.COMMANDS_OWNER, "minecraft.command." + command));
        assertTrue(source(this.owner).hasPermission(Permissions.COMMANDS_OWNER, "minecraft.command." + command));
        assertTrue(source(mock(ConsoleCommandSender.class)).hasPermission(Permissions.COMMANDS_OWNER, "minecraft.command." + command));
    }

    @Test
    void executeEntitySourceAndPermissionCopiesRetainOriginalCaller() {
        final CommandSourceStack original = source(this.operator);
        final ServerPlayer ownerEntity = mock(ServerPlayer.class);
        when(ownerEntity.getUUID()).thenReturn(OWNER);
        assertTrue(this.registry.contains(ownerEntity.getUUID()));
        final CommandSourceStack changed = original.withEntity(ownerEntity)
            .withSource(CommandSource.NULL).withPermission(LevelBasedPermissionSet.GAMEMASTER)
            .withPermission(LevelBasedPermissionSet.OWNER).withSuppressedOutput()
            .withPosition(new Vec3(12, 80, 24)).withRotation(new Vec2(45, 90))
            .withCallback((success, result) -> {});
        assertSame(this.operator, changed.cubveOriginalSender());
        assertFalse(OwnerAccess.canControl(changed));
        assertFalse(changed.hasPermission(Permissions.COMMANDS_OWNER, "minecraft.command.stop"));
        assertTrue(OwnerAccess.canControl(source(this.owner).withSource(CommandSource.NULL).withEntity(mock(ServerPlayer.class))));
    }

    @Test
    void nativeStopChecksAgainAtExecutionDespiteFullVanillaPermissions() throws Exception {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StopCommand.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("stop", source(this.operator)));
        verify(this.minecraft, never()).halt(anyBoolean());
        assertEquals(1, dispatcher.execute("stop", source(this.owner)));
        verify(this.minecraft).halt(false);
    }

    @Test
    void nativeOpDoesNotAllowOperatorToPromoteAnyone() {
        final NameAndId member = new NameAndId(OPERATOR, "Member");
        when(this.minecraft.services().nameToIdCache().get("Member")).thenReturn(Optional.of(member));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        OpCommand.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("op Member", source(this.operator)));
        verify(this.minecraft.getPlayerList(), never()).op(any());
    }

    @Test
    void nativeBanProtectsOfflineOwnerButOperatorCanBanOrdinaryPlayer() throws Exception {
        final NameAndId ownerProfile = new NameAndId(OWNER, "Owner");
        final NameAndId member = new NameAndId(OPERATOR, "Member");
        final UserBanList bans = new UserBanList(this.directory.resolve("bans.json").toFile(), mock(NotificationService.class));
        when(this.minecraft.getPlayerList().getBans()).thenReturn(bans);
        when(this.minecraft.getPlayerList().getPlayer(OPERATOR)).thenReturn(null);
        when(this.minecraft.services().nameToIdCache().get("Owner")).thenReturn(Optional.of(ownerProfile));
        when(this.minecraft.services().nameToIdCache().get("Member")).thenReturn(Optional.of(member));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        BanPlayerCommands.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("ban Owner", source(this.operator)));
        assertFalse(bans.isBanned(ownerProfile));
        assertEquals(1, dispatcher.execute("ban Member", source(this.operator)));
        assertTrue(bans.isBanned(member));
    }

    private ServerPlayer nativePlayer(final UUID id, final String name) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(id);
        when(player.nameAndId()).thenReturn(new NameAndId(id, name));
        when(player.getDisplayName()).thenReturn(Component.literal(name));
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(this.minecraft.getPlayerList().getPlayerByName(name)).thenReturn(player);
        return player;
    }

    @Test
    void nativeKickProtectsOwnerAndRetainsOrdinaryPlayerModeration() throws Exception {
        final ServerPlayer ownerPlayer = nativePlayer(OWNER, "Owner");
        final ServerPlayer member = nativePlayer(OPERATOR, "Member");
        when(this.minecraft.isPublished()).thenReturn(true);
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        KickCommand.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("kick Owner", source(this.operator)));
        verifyNoInteractions(ownerPlayer.connection);
        assertEquals(1, dispatcher.execute("kick Member", source(this.operator)));
        verify(member.connection).disconnect(any(Component.class), eq(org.bukkit.event.player.PlayerKickEvent.Cause.KICKED));
    }

    @Test
    void ipBanRejectsSharedOwnerAddressBeforeWritingOrDisconnecting() {
        final ServerPlayer ownerPlayer = nativePlayer(OWNER, "Owner");
        final IpBanList bans = mock(IpBanList.class);
        when(this.minecraft.getPlayerList().getIpBans()).thenReturn(bans);
        when(this.minecraft.getPlayerList().getPlayersWithAddress("192.0.2.1")).thenReturn(List.of(ownerPlayer));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        BanIpCommands.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("ban-ip 192.0.2.1", source(this.operator)));
        verify(bans, never()).add(any());
        verifyNoInteractions(ownerPlayer.connection);
    }

    @Test
    void ownerHasFullNativePermissionsIndependentlyOfOpsFile() {
        doCallRealMethod().when(this.minecraft).getProfilePermissions(any());
        assertSame(LevelBasedPermissionSet.OWNER, this.minecraft.getProfilePermissions(new NameAndId(OWNER, "Owner")));
        verify(this.minecraft.getPlayerList(), never()).getOps();
    }

    @Test
    void ownerManagementIsConsoleOnlyEvenForAnOwnerWithEveryPermission() throws Exception {
        final io.papermc.paper.command.brigadier.CommandSourceStack playerSource = mock(io.papermc.paper.command.brigadier.CommandSourceStack.class);
        when(playerSource.getSender()).thenReturn(this.owner);
        assertFalse(OwnerCommand.create().build().canUse(playerSource));
        final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getServer()).thenReturn(this.server);
        when(this.server.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(this.server.getHandle()).thenReturn(mock(net.minecraft.server.dedicated.DedicatedPlayerList.class));
        final io.papermc.paper.command.brigadier.CommandSourceStack consoleSource = mock(io.papermc.paper.command.brigadier.CommandSourceStack.class);
        when(consoleSource.getSender()).thenReturn(console);
        final CommandDispatcher<io.papermc.paper.command.brigadier.CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(OwnerCommand.create());
        assertEquals(1, dispatcher.execute("owner add " + OPERATOR, consoleSource));
        assertTrue(this.registry.contains(OPERATOR));
        assertEquals(1, dispatcher.execute("owner remove " + OPERATOR, consoleSource));
        assertFalse(new OwnerRegistry(this.directory.resolve("owners.json")).contains(OPERATOR));
    }

    @Test
    void mixedKickSelectionIsRejectedBeforeAnyDisconnect() {
        final ServerPlayer ownerPlayer = nativePlayer(OWNER, "Owner");
        final ServerPlayer member = nativePlayer(OPERATOR, "Member");
        when(this.minecraft.isPublished()).thenReturn(true);
        when(this.minecraft.getPlayerList().getPlayers()).thenReturn(List.of(member, ownerPlayer));
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        KickCommand.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("kick @a", source(this.operator)));
        verifyNoInteractions(member.connection, ownerPlayer.connection);
    }

    @Test
    void forcedTransferCannotBypassOwnerKickProtection() {
        final ServerPlayer ownerPlayer = nativePlayer(OWNER, "Owner");
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        TransferCommand.register(dispatcher);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("transfer example.invalid 25565 Owner", source(this.operator)));
        verifyNoInteractions(ownerPlayer.connection);
    }

    @Test
    void inheritedServerControlCommandsRejectOpBeforeAnySideEffects() {
        new org.spigotmc.RestartCommand("restart").execute(this.operator, "restart", new String[0]);
        new org.spigotmc.SpigotCommand("spigot").execute(this.operator, "spigot", new String[] {"reload"});
        new io.papermc.paper.command.subcommands.ReloadCommand().execute(this.operator, "reload", new String[0]);
        new org.bukkit.command.defaults.ReloadCommand("reload").execute(this.operator, "reload", new String[] {"confirm"});
        verify(this.server, never()).reload();
        verify(this.server, never()).reloadPermissions();
        verify(this.minecraft, never()).halt(anyBoolean());
        verify(this.operator, times(4)).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void nativeReloadAndAutosaveCannotBeChangedByOp() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        net.minecraft.server.commands.ReloadCommand.register(dispatcher);
        net.minecraft.server.commands.SaveOffCommand.register(dispatcher);
        net.minecraft.server.commands.SaveOnCommand.register(dispatcher);
        net.minecraft.server.commands.ScheduleCommand.register(dispatcher);
        for (String command : List.of("reload", "save-off", "save-on", "schedule clear example:test")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source(this.operator)), command);
        }
        verify(this.minecraft, never()).setAutoSave(anyBoolean());
        verify(this.minecraft, never()).getPackRepository();
    }

    @Test
    void ownerLoginIgnoresUuidAndIpBansWhileOrdinaryBansRemainEffective() throws Exception {
        final NameAndId ownerProfile = new NameAndId(OWNER, "Owner");
        final NameAndId member = new NameAndId(OPERATOR, "Member");
        final UserBanList bans = new UserBanList(this.directory.resolve("banned.json").toFile(), mock(NotificationService.class));
        final IpBanList ipBans = new IpBanList(this.directory.resolve("ip-banned.json").toFile(), mock(NotificationService.class));
        bans.add(new net.minecraft.server.players.UserBanListEntry(ownerProfile));
        bans.add(new net.minecraft.server.players.UserBanListEntry(member));
        ipBans.add(new net.minecraft.server.players.IpBanListEntry("192.0.2.1"));
        final PlayerList players = mock(PlayerList.class);
        for (var entry : java.util.Map.of("server", this.minecraft, "bans", bans, "ipBans", ipBans).entrySet()) {
            final var field = PlayerList.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(players, entry.getValue());
        }
        when(players.isWhiteListedLogin(any())).thenReturn(PlayerList.LoginResult.ALLOW);
        when(players.canBypassFullServerLogin(any(), any())).thenReturn(PlayerList.LoginResult.ALLOW);
        doCallRealMethod().when(players).canPlayerLogin(any(), any());
        final var address = new java.net.InetSocketAddress("192.0.2.1", 12345);
        assertTrue(players.canPlayerLogin(address, ownerProfile).isAllowed());
        assertEquals(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED, players.canPlayerLogin(address, member).result());
        bans.remove(member);
        assertEquals(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_BANNED, players.canPlayerLogin(address, member).result());
    }

    @Test
    void unprivilegedPlayersDoNotGainModerationPermissions() {
        final Player member = player(UUID.randomUUID(), "Unprivileged");
        when(member.isOp()).thenReturn(false);
        when(member.hasPermission(anyString())).thenReturn(false);
        final CommandSourceStack unprivileged = source(member).withPermission(LevelBasedPermissionSet.ALL);
        assertFalse(unprivileged.hasPermission(Permissions.COMMANDS_ADMIN, "minecraft.command.kick"));
        assertFalse(unprivileged.hasPermission(Permissions.COMMANDS_ADMIN, "minecraft.command.ban"));
    }

    @Test
    void namespacedAliasStillRunsNativeOwnerCheck() {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        StopCommand.register(dispatcher);
        dispatcher.register(net.minecraft.commands.Commands.literal("minecraft:stop").redirect(dispatcher.getRoot().getChild("stop")));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("minecraft:stop", source(this.operator)));
        verify(this.minecraft, never()).halt(anyBoolean());
    }

    @Test
    void bukkitKickApisCannotBeUsedToKickAnOwner() throws Exception {
        final org.bukkit.craftbukkit.entity.CraftPlayer player = mock(org.bukkit.craftbukkit.entity.CraftPlayer.class);
        final var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(player, this.server);
        when(player.getUniqueId()).thenReturn(OWNER);
        doCallRealMethod().when(player).kickPlayer(anyString());
        doCallRealMethod().when(player).kick(any(net.kyori.adventure.text.Component.class), any());
        assertThrows(IllegalArgumentException.class, () -> player.kickPlayer("test"));
        assertThrows(IllegalArgumentException.class, () -> player.kick(net.kyori.adventure.text.Component.text("test"), org.bukkit.event.player.PlayerKickEvent.Cause.PLUGIN));
        verify(player, never()).getHandle();
    }

    @Test
    void bukkitBanApisCannotWriteOwnerOrSharedOwnerIpBan() {
        try (var staticServer = mockStatic(MinecraftServer.class)) {
            staticServer.when(MinecraftServer::getServer).thenReturn(this.minecraft);
            final UserBanList bans = mock(UserBanList.class);
            final var profiles = new org.bukkit.craftbukkit.ban.CraftProfileBanList(bans);
            assertThrows(IllegalArgumentException.class, () -> profiles.addBan(new NameAndId(OWNER, "Owner"), "test", null, "plugin"));
            verify(bans, never()).add(any());
            final ServerPlayer ownerPlayer = nativePlayer(OWNER, "Owner");
            when(this.minecraft.getPlayerList().getPlayersWithAddress("192.0.2.1")).thenReturn(List.of(ownerPlayer));
            final IpBanList ipBans = mock(IpBanList.class);
            final var ips = new org.bukkit.craftbukkit.ban.CraftIpBanList(ipBans);
            assertThrows(IllegalArgumentException.class, () -> ips.addBan("192.0.2.1", "test", (java.util.Date) null, "plugin"));
            verify(ipBans, never()).add(any());
        }
    }

    @Test
    void whitelistVerificationRecognizesOwnerOutsideOpsFile() throws Exception {
        final PlayerList players = mock(PlayerList.class);
        final var ops = new net.minecraft.server.players.ServerOpList(this.directory.resolve("ops.json").toFile(), mock(NotificationService.class));
        final var whitelist = new net.minecraft.server.players.UserWhiteList(this.directory.resolve("whitelist.json").toFile(), mock(NotificationService.class));
        for (var entry : java.util.Map.of("server", this.minecraft, "ops", ops, "whitelist", whitelist).entrySet()) {
            final var field = PlayerList.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(players, entry.getValue());
        }
        when(players.isUsingWhitelist()).thenReturn(true);
        doCallRealMethod().when(players).isOp(any());
        doCallRealMethod().when(players).isWhiteListedLogin(any());
        final String previousMessage = org.spigotmc.SpigotConfig.whitelistMessage;
        org.spigotmc.SpigotConfig.whitelistMessage = "Whitelist required";
        try {
            assertTrue(players.isOp(new NameAndId(OWNER, "Owner")));
            assertTrue(players.isWhiteListedLogin(new NameAndId(OWNER, "Owner")).isAllowed());
            assertEquals(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_WHITELIST, players.isWhiteListedLogin(new NameAndId(OPERATOR, "Member")).result());
            assertTrue(ops.getEntries().isEmpty());
        } finally {
            org.spigotmc.SpigotConfig.whitelistMessage = previousMessage;
        }
    }
}
