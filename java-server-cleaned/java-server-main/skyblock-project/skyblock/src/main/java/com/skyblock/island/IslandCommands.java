package com.skyblock.island;

import com.skyblock.island.IslandGenerator;
import com.skyblock.island.InviteSystem;
import com.skyblock.island.IslandMenu;
import com.skyblock.pwarp.PwarpSystem;
import com.skyblock.storage.IslandData;
import com.skyblock.storage.IslandStorage;
import com.skyblock.util.NameValidator;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Logger;

/**
 * Island Commands
 *
 * Translated from island_commands.js.
 *
 * Implements all island management commands as Bukkit CommandExecutors.
 * Register in plugin.yml:
 *
 *   commands:
 *     is:
 *       description: Skyblock island commands
 *       usage: /is <create|go|spawn|leave|reset|confirm|accept|deny|info|invite|remove|setrank|op|name|help>
 *     setwarp:
 *       description: Set a public warp at your location
 *     delwarp:
 *       description: Delete a public warp
 *     crates:
 *       description: Teleport to crates
 */
public class IslandCommands implements CommandExecutor {

    // ─── Spawn / crates locations (mirror JS constants) ───────────────────────
    private static final double SPAWN_X = 0.5,  SPAWN_Y = 286,  SPAWN_Z = 0.5;
    private static final float  SPAWN_YAW = 180f, SPAWN_PITCH = 0f;

    private static final double CRATES_X = 15.5, CRATES_Y = 286, CRATES_Z = 0.5;
    private static final float  CRATES_YAW = -90f;

    // ─── Rank tables (mirror JS RANK_LEVELS / RANK_MAP) ──────────────────────
    private static final Map<String, Integer> RANK_LEVELS = Map.of(
            "Member", 0, "Moderator", 1, "Admin", 2, "Co-Owner", 3, "Owner", 4);

    private static final Map<String, String> RANK_MAP = Map.of(
            "member",   "Member",
            "moderator","Moderator",
            "mod",      "Moderator",
            "admin",    "Admin",
            "coowner",  "Co-Owner",
            "co-owner", "Co-Owner",
            "coo",      "Co-Owner");

    // ─── Confirm-pending (mirror JS confirmPending Map) ───────────────────────
    private static class Pending {
        String action;   // "leave" | "reset" | "transfer"
        String targetId;
        String targetName;
    }
    private final Map<UUID, Pending> confirmPending = new HashMap<>();

    // ─── Dependencies ─────────────────────────────────────────────────────────
    private final IslandStorage   storage;
    private final IslandGenerator generator;
    private final InviteSystem    invites;
    private final PwarpSystem     pwarp;
    private final JavaPlugin      plugin;
    private final Logger          logger;
    private IslandMenu            islandMenu; // set after construction to avoid circular dep

    public IslandCommands(IslandStorage storage, IslandGenerator generator,
                          InviteSystem invites, PwarpSystem pwarp,
                          JavaPlugin plugin, Logger logger) {
        this.storage   = storage;
        this.generator = generator;
        this.invites   = invites;
        this.pwarp     = pwarp;
        this.plugin    = plugin;
        this.logger    = logger;
    }

    public void setIslandMenu(IslandMenu menu) {
        this.islandMenu = menu;
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private World overworld() { return plugin.getServer().getWorld("world"); }

    private Player online(String name) {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private Player onlineById(String uuid) {
        try { return plugin.getServer().getPlayer(UUID.fromString(uuid)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private void notifyMembers(IslandData island, String excludeId, String msg) {
        for (IslandData.Member m : island.members) {
            if (!m.id.equals(excludeId)) {
                Player p = onlineById(m.id);
                if (p != null) p.sendMessage(msg);
            }
        }
    }

    private Location spawnLoc() {
        return new Location(overworld(), SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
    }

    private Location cratesLoc() {
        return new Location(overworld(), CRATES_X, CRATES_Y, CRATES_Z, CRATES_YAW, 0f);
    }

    // ─── CommandExecutor dispatch ─────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }

        String name = cmd.getName().toLowerCase(Locale.ROOT);

        switch (name) {
            case "is"        -> handleIs(player, args);
            case "setwarp"   -> { if (args.length < 1) { player.sendMessage("§cUsage: /setwarp <name>"); } else handleSetwarp(player, args[0]); }
            case "delwarp"   -> { if (args.length < 1) { player.sendMessage("§cUsage: /delwarp <name>"); } else handleDelwarp(player, args[0]); }
            case "crates"    -> handleCrates(player);
        }
        return true;
    }

    private void handleIs(Player player, String[] args) {
        // Bare /is → open the island GUI
        if (args.length == 0) {
            if (islandMenu != null) { islandMenu.showIslandMenu(player); return; }
            handleHelp(player); return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create"  -> handleCreate(player);
            case "go"      -> handleGo(player);
            case "spawn"   -> handleSpawn(player);
            case "leave"   -> handleLeave(player);
            case "reset"   -> handleReset(player);
            case "confirm" -> handleConfirm(player);
            case "accept"  -> handleAccept(player);
            case "deny"    -> handleDeny(player);
            case "info"    -> handleInfo(player);
            case "invite"  -> { if (args.length < 2) { player.sendMessage("§cUsage: /is invite <player>"); } else handleInvite(player, args[1]); }
            case "remove"  -> { if (args.length < 2) { player.sendMessage("§cUsage: /is remove <player>"); } else handleRemove(player, args[1]); }
            case "setrank" -> { if (args.length < 3) { player.sendMessage("§cUsage: /is setrank <player> <rank>"); } else handleSetRank(player, args[1], args[2]); }
            case "op"      -> { if (args.length < 2) { player.sendMessage("§cUsage: /is op <player>"); } else handleIsop(player, args[1]); }
            case "name"    -> { if (args.length < 2) { player.sendMessage("§cUsage: /is name <name>"); } else handleSetname(player, args[1]); }
            default        -> handleHelp(player);
        }
    }

    // ─── /is create ───────────────────────────────────────────────────────────

    private void handleCreate(Player player) {
        if (storage.getIslandByPlayerId(player.getUniqueId().toString()) != null) {
            player.sendMessage("§cYou already have an island. Use §e/is go§c."); return;
        }
        IslandData island = storage.createIsland(
                player.getUniqueId().toString(), player.getName());
        IslandGenerator.Result gen =
                generator.generateIsland(island.centerX, island.centerZ, overworld());
        Map<String, Object> updates = new HashMap<>();
        updates.put("homeX", gen.homeX); updates.put("homeY", gen.homeY); updates.put("homeZ", gen.homeZ);
        storage.updateIsland(island.id, updates);
        player.teleport(new Location(overworld(), gen.homeX, 75, gen.homeZ));
        player.sendMessage("§a§lIsland created! §r§aWelcome to your island!");
    }

    // ─── /is go ───────────────────────────────────────────────────────────────

    private void handleGo(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cNo island. Use §e/is create§c."); return; }
        player.teleport(new Location(overworld(), island.homeX, island.homeY == 0 ? 75 : island.homeY, island.homeZ));
        player.sendMessage("§aTeleported to your island!");
    }

    // ─── /is spawn ────────────────────────────────────────────────────────────

    private void handleSpawn(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        String role = storage.getMemberRole(island, player.getUniqueId().toString());
        if (!List.of("Owner","Co-Owner").contains(role)) {
            player.sendMessage("§cOnly the Owner or Co-Owner can set island spawn."); return;
        }
        Location loc = player.getLocation();
        Map<String, Object> u = new HashMap<>();
        u.put("homeX", loc.getX()); u.put("homeY", loc.getY()); u.put("homeZ", loc.getZ());
        storage.updateIsland(island.id, u);
        player.sendMessage("§aIsland spawn set to §e" + (int)loc.getX()
                + ", " + (int)loc.getY() + ", " + (int)loc.getZ() + "§a!");
    }

    // ─── /is leave ────────────────────────────────────────────────────────────

    private void handleLeave(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou are not on an island."); return; }

        boolean isOwner = island.ownerId.equals(player.getUniqueId().toString());

        // Always require confirm if: owner (island will be deleted or new owner auto-assigned),
        // or sole member (island deleted).
        if (isOwner || island.members.size() == 1) {
            Pending p = new Pending(); p.action = "leave";
            confirmPending.put(player.getUniqueId(), p);
            if (isOwner && island.members.size() > 1) {
                player.sendMessage("§c§l⚠ WARNING: §r§cYou are the Owner. Leaving will delete the island for everyone!");
            } else {
                player.sendMessage("§c§l⚠ WARNING: §r§cLeaving will permanently delete your island!");
            }
            player.sendMessage("§cType §e/is confirm §cwithin 30 seconds to proceed.");
            new BukkitRunnable() {
                @Override public void run() {
                    Pending cur = confirmPending.get(player.getUniqueId());
                    if (cur != null && "leave".equals(cur.action)) {
                        confirmPending.remove(player.getUniqueId());
                        player.sendMessage("§7Leave cancelled.");
                    }
                }
            }.runTaskLater(plugin, 600L);
            return;
        }
        doLeave(player, island);
    }

    private void doLeave(Player player, IslandData island) {
        IslandStorage.RemoveResult result =
                storage.removeMember(island.id, player.getUniqueId().toString());
        player.teleport(spawnLoc());
        if (result.deleted) {
            player.sendMessage("§eIsland deleted.");
        } else {
            player.sendMessage("§eYou left your island.");
            notifyMembers(result.island, player.getUniqueId().toString(),
                    "§e" + player.getName() + " §7left the island.");
            if (!result.island.ownerId.equals(island.ownerId)) {
                Player newOwner = onlineById(result.island.ownerId);
                if (newOwner != null) newOwner.sendMessage("§6§lYou are now the island Owner!");
            }
        }
    }

    // ─── /is reset ────────────────────────────────────────────────────────────

    private void handleReset(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        if (!"Owner".equals(storage.getMemberRole(island, player.getUniqueId().toString()))) {
            player.sendMessage("§cOnly the Owner can reset."); return;
        }
        Pending p = new Pending(); p.action = "reset";
        confirmPending.put(player.getUniqueId(), p);
        player.sendMessage("§c§l⚠ WARNING: §r§cThis will destroy and regenerate your island!");
        player.sendMessage("§cType §e/is confirm §cwithin 30 seconds to proceed.");
        new BukkitRunnable() {
            @Override public void run() {
                Pending cur = confirmPending.get(player.getUniqueId());
                if (cur != null && "reset".equals(cur.action)) {
                    confirmPending.remove(player.getUniqueId());
                    player.sendMessage("§7Reset cancelled.");
                }
            }
        }.runTaskLater(plugin, 600L);
    }

    // ─── /is confirm ──────────────────────────────────────────────────────────

    private void handleConfirm(Player player) {
        Pending pending = confirmPending.remove(player.getUniqueId());
        if (pending == null) {
            player.sendMessage("§cNothing to confirm. Use §e/is reset§c, §e/is leave§c, or §e/is op§c first."); return;
        }
        switch (pending.action) {
            case "leave" -> {
                IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
                if (island == null) { player.sendMessage("§cNo island found."); return; }
                doLeave(player, island);
            }
            case "reset" -> {
                IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
                if (island == null) { player.sendMessage("§cNo island found."); return; }
                for (IslandData.Member m : island.members) {
                    Player p = onlineById(m.id);
                    if (p != null) { p.teleport(spawnLoc()); p.sendMessage("§cIsland is resetting..."); }
                }
                generator.clearIsland(island.centerX, island.centerZ, overworld());
                IslandGenerator.Result gen = generator.generateIsland(island.centerX, island.centerZ, overworld());
                Map<String, Object> u = new HashMap<>();
                u.put("homeX", gen.homeX); u.put("homeY", gen.homeY); u.put("homeZ", gen.homeZ);
                u.put("pwarps", new ArrayList<>());
                storage.updateIsland(island.id, u);
                new BukkitRunnable() {
                    @Override public void run() {
                        player.teleport(new Location(overworld(), gen.homeX, 75, gen.homeZ));
                        player.sendMessage("§aIsland reset complete!");
                    }
                }.runTaskLater(plugin, 20L);
            }
            case "transfer" -> {
                IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
                if (island == null) { player.sendMessage("§cNo island found."); return; }
                IslandData.Member target = island.members.stream()
                        .filter(m -> m.id.equals(pending.targetId)).findFirst().orElse(null);
                if (target == null) {
                    player.sendMessage("§c" + pending.targetName + " is no longer on your island."); return;
                }
                storage.setMemberRole(island.id, player.getUniqueId().toString(), "Member");
                storage.setMemberRole(island.id, target.id, "Owner");
                Map<String, Object> u = new HashMap<>();
                u.put("ownerId", target.id); u.put("ownerName", target.name);
                storage.updateIsland(island.id, u);
                player.sendMessage("§aOwnership transferred to §e" + target.name + "§a!");
                Player t = onlineById(target.id);
                if (t != null) t.sendMessage("§6§lYou are now the island Owner!");
                notifyMembers(island, player.getUniqueId().toString(),
                        "§e" + target.name + " §6is now the island Owner.");
            }
        }
    }

    // ─── /is accept ───────────────────────────────────────────────────────────

    private void handleAccept(Player player) {
        InviteSystem.InviteEntry invite = invites.getInvite(player.getUniqueId().toString());
        if (invite == null) { player.sendMessage("§cNo pending invite."); return; }

        IslandData myIsland = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (myIsland != null) {
            // If they are the sole member, auto-delete their island so they can join
            if (myIsland.members.size() == 1) {
                storage.removeMember(myIsland.id, player.getUniqueId().toString());
                player.sendMessage("§7Your solo island was automatically removed so you could join.");
            } else {
                player.sendMessage("§cYou are on an island with other members. Leave it first with §e/is leave§c.");
                return;
            }
        }

        IslandData island = storage.getIslandById(invite.islandId);
        if (island == null) { invites.clearInvite(player.getUniqueId().toString()); player.sendMessage("§cThat island no longer exists."); return; }
        if (island.members.size() >= island.maxMembers) { invites.clearInvite(player.getUniqueId().toString()); player.sendMessage("§cThat island is now full."); return; }
        invites.clearInvite(player.getUniqueId().toString());
        storage.addMember(island.id, player.getUniqueId().toString(), player.getName(), "Member");
        player.teleport(new Location(overworld(), island.homeX, island.homeY == 0 ? 75 : island.homeY, island.homeZ));
        player.sendMessage("§aJoined §e" + island.ownerName + "'s §aisland!");
        notifyMembers(island, player.getUniqueId().toString(), "§e" + player.getName() + " §ajoined the island!");
    }

    // ─── /is deny ─────────────────────────────────────────────────────────────

    private void handleDeny(Player player) {
        InviteSystem.InviteEntry invite = invites.getInvite(player.getUniqueId().toString());
        if (invite == null) { player.sendMessage("§cNo pending invite."); return; }
        invites.clearInvite(player.getUniqueId().toString());
        player.sendMessage("§eDeclined invite from §c" + invite.inviterName + "§e.");
        Player inviter = onlineById(invite.inviterId);
        if (inviter != null) inviter.sendMessage("§c" + player.getName() + " §edeclined your invite.");
    }

    // ─── /is info ─────────────────────────────────────────────────────────────

    private void handleInfo(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cNo island. Use §e/is create§c."); return; }
        player.sendMessage("§6§l--- Island Info ---");
        player.sendMessage("§7Name: §e" + island.name + "  §7Owner: §e" + island.ownerName);
        player.sendMessage("§7Members (§e" + island.members.size() + "§7/§e" + island.maxMembers + "§7):");
        for (IslandData.Member m : island.members)
            player.sendMessage("  §7- §e" + m.name + " §7[§6" + m.role + "§7]");
        if (!island.pwarps.isEmpty()) {
            StringBuilder wb = new StringBuilder("§7Warps: ");
            for (int i = 0; i < island.pwarps.size(); i++) {
                if (i > 0) wb.append("§7, ");
                wb.append("§b").append(island.pwarps.get(i).name);
            }
            player.sendMessage(wb.toString());
        }
    }

    // ─── /is help ─────────────────────────────────────────────────────────────

    private void handleHelp(Player player) {
        player.sendMessage("§5§l--- Skyblock Commands ---");
        player.sendMessage("§e/is create §7- Create your island");
        player.sendMessage("§e/is go §7- Teleport to your island");
        player.sendMessage("§e/is spawn §7- Set island spawn to your location");
        player.sendMessage("§e/is leave §7- Leave your island");
        player.sendMessage("§e/is reset §7- Reset your island");
        player.sendMessage("§e/is confirm §7- Confirm a pending action");
        player.sendMessage("§e/is info §7- View island info");
        player.sendMessage("§e/is accept §7- Accept a pending invite");
        player.sendMessage("§e/is deny §7- Deny a pending invite");
        player.sendMessage("§e/is invite <player> §7- Invite a player");
        player.sendMessage("§e/is remove <player> §7- Remove a member");
        player.sendMessage("§e/is setrank <player> <rank> §7- Set rank (member/moderator/admin/coowner)");
        player.sendMessage("§e/is op <player> §7- Transfer island ownership");
        player.sendMessage("§e/is name <name> §7- Rename your island");
        player.sendMessage("§e/is help §7- Show this list");
        player.sendMessage("§e/setwarp <name> §7- Set a public warp here");
        player.sendMessage("§e/delwarp <name> §7- Delete a warp");
        player.sendMessage("§e/pwarp §7- Browse all public warps");
        player.sendMessage("§e/pwarp <warp> §7- Teleport to a public warp");
        player.sendMessage("§e/hub §7- Teleport to hub/spawn");
        player.sendMessage("§e/crates §7- Teleport to crates");
    }

    // ─── /invite <player> ─────────────────────────────────────────────────────

    private void handleInvite(Player player, String targetName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
        if (!List.of("Owner","Co-Owner","Admin").contains(myRole)) {
            player.sendMessage("§cOnly Owners, Co-Owners and Admins can invite."); return;
        }
        if (island.members.size() >= island.maxMembers) {
            player.sendMessage("§cIsland full! (" + island.members.size() + "/" + island.maxMembers + ")"); return;
        }
        Player target = online(targetName);
        if (target == null) { player.sendMessage("§c" + targetName + " is not online."); return; }
        invites.createInvite(island.id, player.getUniqueId().toString(), player.getName(),
                target.getUniqueId().toString(), target.getName());
        player.sendMessage("§aInvited §e" + target.getName() + "§a to your island!");
        target.sendMessage("§e" + player.getName() + " §ainvited you to their island!");
        target.sendMessage("§7Use §e/is accept §7to join, or §e/is deny §7to decline.");
        if (storage.getIslandByPlayerId(target.getUniqueId().toString()) != null)
            target.sendMessage("§7(You have an island — leave it first to accept.)");
    }

    // ─── /is remove <player> ────────────────────────────────────────────────────

    private void handleRemove(Player player, String targetName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
        if (!List.of("Owner","Co-Owner","Admin").contains(myRole)) {
            player.sendMessage("§cOnly Owners, Co-Owners and Admins can remove members."); return;
        }
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) { player.sendMessage("§c" + targetName + " is not on your island."); return; }
        if (target.id.equals(player.getUniqueId().toString())) { player.sendMessage("§cUse §e/is leave§c."); return; }
        int myLevel = RANK_LEVELS.getOrDefault(myRole, 0);
        int targetLevel = RANK_LEVELS.getOrDefault(target.role, 0);
        if (!"Owner".equals(myRole) && myLevel <= targetLevel) {
            player.sendMessage("§cYou cannot remove someone at or above your rank."); return;
        }
        storage.removeMember(island.id, target.id);
        player.sendMessage("§eRemoved §c" + target.name + "§e.");
        Player t = onlineById(target.id);
        if (t != null) {
            t.sendMessage("§cYou were removed from §e" + island.ownerName + "'s §cisland.");
            t.teleport(spawnLoc());
        }
    }

    // ─── /is setrank <player> <rank> ──────────────────────────────────────────

    private void handleSetRank(Player player, String targetName, String rankArg) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
        if (!List.of("Owner","Co-Owner").contains(myRole)) {
            player.sendMessage("§cOnly Owners and Co-Owners can set ranks."); return;
        }
        String newRole = RANK_MAP.get(rankArg.toLowerCase(Locale.ROOT));
        if (newRole == null) {
            player.sendMessage("§cUnknown rank §e" + rankArg + "§c. Valid: §emember, moderator, admin, coowner"); return;
        }
        if ("Owner".equals(newRole)) { player.sendMessage("§cUse §e/is op <player> §cto transfer ownership."); return; }
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) { player.sendMessage("§c" + targetName + " not found."); return; }
        if (target.id.equals(player.getUniqueId().toString())) { player.sendMessage("§cCannot change your own rank."); return; }
        if ("Co-Owner".equals(myRole) && RANK_LEVELS.getOrDefault(target.role, 0) >= RANK_LEVELS.get("Co-Owner")) {
            player.sendMessage("§cCo-Owners cannot change ranks of Co-Owners or higher."); return;
        }
        if ("Co-Owner".equals(myRole) && RANK_LEVELS.getOrDefault(newRole, 0) >= RANK_LEVELS.get("Co-Owner")) {
            player.sendMessage("§cCo-Owners can only assign ranks below Co-Owner."); return;
        }
        storage.setMemberRole(island.id, target.id, newRole);
        player.sendMessage("§aSet §e" + target.name + "'s §arank to §6" + newRole + "§a.");
        Player t = onlineById(target.id);
        if (t != null) t.sendMessage("§aYour rank was set to §6" + newRole + "§a.");
    }

    // ─── /is op <player> ───────────────────────────────────────────────────────

    private void handleIsop(Player player, String targetName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        if (!"Owner".equals(storage.getMemberRole(island, player.getUniqueId().toString()))) {
            player.sendMessage("§cOnly the Owner can transfer ownership."); return;
        }
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) { player.sendMessage("§c" + targetName + " not found."); return; }
        if (target.id.equals(player.getUniqueId().toString())) { player.sendMessage("§cYou are already the Owner."); return; }
        Pending p = new Pending(); p.action = "transfer"; p.targetId = target.id; p.targetName = target.name;
        confirmPending.put(player.getUniqueId(), p);
        player.sendMessage("§eTransfer ownership to §a" + target.name
                + "§e? Type §e/is confirm §ewithin 30s. §cThis cannot be undone.");
        new BukkitRunnable() {
            @Override public void run() {
                Pending cur = confirmPending.get(player.getUniqueId());
                if (cur != null && "transfer".equals(cur.action)) {
                    confirmPending.remove(player.getUniqueId());
                    player.sendMessage("§7Ownership transfer cancelled.");
                }
            }
        }.runTaskLater(plugin, 600L);
    }

    // ─── /is name <name> ──────────────────────────────────────────────────────

    private void handleSetname(Player player, String newName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou don't have an island."); return; }
        if (!"Owner".equals(storage.getMemberRole(island, player.getUniqueId().toString()))) {
            player.sendMessage("§cOnly the Owner can rename the island."); return;
        }
        NameValidator.Result v = NameValidator.validate(newName);
        if (!v.valid) { player.sendMessage(v.reason); return; }
        IslandData conflict = storage.getIslandByName(newName);
        if (conflict != null && !conflict.id.equals(island.id)) {
            player.sendMessage("§cName §e" + newName + " §cis already taken."); return;
        }
        Map<String, Object> u = new HashMap<>(); u.put("name", newName);
        storage.updateIsland(island.id, u);
        player.sendMessage("§aIsland renamed to §e" + newName + "§a!");
    }

    // ─── /setwarp <name> ──────────────────────────────────────────────────────

    private void handleSetwarp(Player player, String warpName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou must be on an island to set a warp."); return; }
        String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
        if (myRole == null) { player.sendMessage("§cYou are not a member of this island."); return; }
        NameValidator.Result v = NameValidator.validate(warpName);
        if (!v.valid) { player.sendMessage(v.reason); return; }
        String lowerWarp = warpName.toLowerCase(Locale.ROOT);
        IslandData.Warp existingHere = island.pwarps.stream()
                .filter(p -> p.name.toLowerCase(Locale.ROOT).equals(lowerWarp)).findFirst().orElse(null);
        if (pwarp.isPwarpNameTaken(warpName) && existingHere == null) {
            player.sendMessage("§cWarp §e" + warpName + " §cis already used on another island."); return;
        }
        if (existingHere != null && !existingHere.creatorId.equals(player.getUniqueId().toString())
                && !List.of("Owner","Co-Owner","Admin").contains(myRole)) {
            player.sendMessage("§cCan't overwrite §e" + existingHere.creatorName + "'s §cwarp."); return;
        }
        Location loc = player.getLocation();
        storage.addPwarp(island.id, warpName, loc.getX(), loc.getY(), loc.getZ(),
                player.getUniqueId().toString(), player.getName());
        player.sendMessage("§aWarp §b" + warpName + " §aset! Visit with: §e/pwarp " + warpName);
    }

    // ─── /delwarp <name> ──────────────────────────────────────────────────────

    private void handleDelwarp(Player player, String warpName) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) { player.sendMessage("§cYou must be on an island."); return; }
        String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
        String lowerWarp = warpName.toLowerCase(Locale.ROOT);
        IslandData.Warp warpEntry = island.pwarps.stream()
                .filter(p -> p.name.toLowerCase(Locale.ROOT).equals(lowerWarp)).findFirst().orElse(null);
        if (warpEntry == null) { player.sendMessage("§cNo warp §e" + warpName + "§c."); return; }
        if (!warpEntry.creatorId.equals(player.getUniqueId().toString())
                && !List.of("Owner","Co-Owner","Admin").contains(myRole)) {
            player.sendMessage("§cYou can only delete your own warps."); return;
        }
        storage.removePwarp(island.id, warpName);
        player.sendMessage("§aWarp §b" + warpName + " §adeleted.");
    }

    // ─── /crates ──────────────────────────────────────────────────────────────

    private void handleCrates(Player player) {
        player.teleport(cratesLoc());
        player.sendMessage("§aTeleported to crates!");
    }
}
