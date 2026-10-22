package io.github.lunastev.cubve.fixture;

import io.github.lunastev.cubve.permission.OwnerAccess;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin. Synthetic Bukkit player identities are not connected clients. */
public final class OwnerProbe extends JavaPlugin {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final List<String> checks = new ArrayList<>();
    private final List<String> feedback = new ArrayList<>();

    @Override
    public void onEnable() {
        java.util.Objects.requireNonNull(getCommand("cubveownerprobe")).setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) return false;
        final boolean stopAsOwner = args.length == 1 && args[0].equals("stop-as-owner");
        // Leave the outer native execution context, as in the clone fixture.
        getServer().getScheduler().runTask(this, () -> {
            try {
                runChecks(stopAsOwner);
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "CUBVE_OWNER_PROBE_FAIL", failure);
            }
        });
        return true;
    }

    private Player player(UUID id, String name) {
        return (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "getServer" -> getServer();
            case "hasPermission", "isPermissionSet", "isOp" -> true;
            case "sendMessage" -> { this.feedback.add(java.util.Arrays.toString(args)); yield null; }
            case "toString" -> "OwnerFixture[" + name + "]";
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unexpected fixture player method: " + method);
        });
    }

    private CommandSourceStack source(MinecraftServer server, Player player) {
        final CommandSource origin = new CommandSource() {
            public void sendSystemMessage(Component message) { feedback.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
            public CommandSender getBukkitSender(CommandSourceStack stack) { return player; }
        };
        return new CommandSourceStack(origin, new Vec3(0, 120, 0), Vec2.ZERO, server.overworld(),
            LevelBasedPermissionSet.OWNER, Component.literal(player.getName()), server);
    }

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label + "; feedback=" + this.feedback);
        this.checks.add(label);
    }

    private void execute(MinecraftServer server, CommandSourceStack source, String command, boolean allowed) {
        final int[] callbacks = {0};
        final boolean[] success = {false};
        server.getCommands().performPrefixedCommand(source.withCallback((ok, value) -> {
            callbacks[0]++;
            success[0] = ok;
        }), command);
        check(allowed ? callbacks[0] == 1 && success[0] : !success[0], (allowed ? "allow:" : "deny:") + command);
    }

    private void runChecks(boolean stopAsOwner) {
        final CraftServer craft = (CraftServer) getServer();
        final MinecraftServer server = craft.getServer();
        final NameAndId ownerProfile = new NameAndId(OWNER, "OwnerProbe");
        final NameAndId opProfile = new NameAndId(OP, "OperatorProbe");
        final NameAndId memberProfile = new NameAndId(MEMBER, "MemberProbe");
        for (NameAndId profile : List.of(ownerProfile, opProfile, memberProfile)) server.services().nameToIdCache().add(profile);
        server.getPlayerList().op(opProfile);
        check(craft.cubveOwners().contains(OWNER), "owner-loaded");
        check(!craft.cubveOwners().contains(OP), "ordinary-op-is-not-owner");
        check(server.getPlayerList().isOp(ownerProfile), "implicit-owner-op");
        check(server.getPlayerList().getOps().get(ownerProfile) == null, "owner-independent-of-ops-file");
        check(server.getProfilePermissions(ownerProfile) == LevelBasedPermissionSet.OWNER, "owner-full-native-permissions");
        final CommandSourceStack owner = source(server, player(OWNER, "OwnerProbe"));
        final CommandSourceStack operator = source(server, player(OP, "OperatorProbe"));
        for (String command : List.of("stop", "minecraft:stop", "execute run stop", "minecraft:op MemberProbe", "deop OwnerProbe",
                "reload", "save-off", "save-on", "datapack list", "schedule clear minecraft:fixture")) {
            execute(server, operator, command, false);
        }
        execute(server, operator.withSource(CommandSource.NULL), "minecraft:stop", false);
        execute(server, operator, "owner add " + OP, false);
        execute(server, owner, "owner add " + OP, false);
        check(!craft.cubveOwners().contains(OP), "players-cannot-manage-owners");
        execute(server, operator, "ban OwnerProbe", false);
        check(!server.getPlayerList().getBans().isBanned(ownerProfile), "owner-ban-list-unchanged");
        execute(server, operator, "ban MemberProbe", true);
        check(server.getPlayerList().getBans().isBanned(memberProfile), "ordinary-op-can-ban-member");
        execute(server, operator, "pardon MemberProbe", true);
        execute(server, operator, "difficulty", true);
        execute(server, owner, "save-off", true);
        execute(server, owner, "save-on", true);
        execute(server, owner, "op MemberProbe", true);
        check(server.getPlayerList().isOp(memberProfile), "owner-can-grant-op");
        execute(server, owner, "deop MemberProbe", true);
        check(!server.getPlayerList().isOp(memberProfile), "owner-can-revoke-op");
        for (String command : List.of("restart", "spigot reload", "paper reload", "bukkit:reload confirm")) {
            this.feedback.clear();
            server.getCommands().performPrefixedCommand(operator, command);
            check(this.feedback.stream().anyMatch(text -> text.contains("Only a Cubve owner")), "legacy-deny:" + command);
        }
        final var bans = server.getPlayerList().getBans();
        bans.add(new UserBanListEntry(ownerProfile));
        check(server.getPlayerList().canPlayerLogin(new InetSocketAddress("192.0.2.1", 12345), ownerProfile).isAllowed(), "owner-login-protected");
        bans.remove(ownerProfile);
        final var profileApi = new org.bukkit.craftbukkit.ban.CraftProfileBanList(bans);
        boolean apiDenied = false;
        try { profileApi.addBan(ownerProfile, "test", null, "fixture"); } catch (IllegalArgumentException expected) { apiDenied = true; }
        check(apiDenied && !bans.isBanned(ownerProfile), "profile-ban-api-protected");
        check(OwnerAccess.canControl(owner) && !OwnerAccess.canControl(operator), "roles-still-distinct");
        getLogger().info("CUBVE_OWNER_PROBE_PASS checks=" + this.checks.size() + " stopAsOwner=" + stopAsOwner);
        if (stopAsOwner) execute(server, owner, "minecraft:stop", true);
    }
}
