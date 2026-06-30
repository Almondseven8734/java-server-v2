package com.skyblock.tpa;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * TPA System
 *
 * Commands:
 *   /tpa <player>     — request to teleport TO that player
 *   /tphere <player>  — request to teleport that player TO you
 *   /tpaccept         — accept the pending teleport request
 *   /tpdeny           — deny the pending teleport request
 *
 * A request expires after 60 seconds if not answered.
 *
 * Register in plugin.yml:
 *   commands:
 *     tpa:
 *       description: Request to teleport to a player
 *       usage: /tpa <player>
 *     tphere:
 *       description: Request a player to teleport to you
 *       usage: /tphere <player>
 *     tpaccept:
 *       description: Accept a pending teleport request
 *       usage: /tpaccept
 *     tpdeny:
 *       description: Deny a pending teleport request
 *       usage: /tpdeny
 */
public class TpaSystem implements CommandExecutor, Listener {

    /** How long (seconds) before a TPA request expires. */
    private static final int REQUEST_TIMEOUT_SECS = 60;

    /**
     * Tracks pending requests.
     *
     * Key   = UUID of the player who must ANSWER (/tpaccept or /tpdeny)
     * Value = TpaRequest describing who sent it and what kind it is
     */
    private final Map<UUID, TpaRequest> pendingRequests = new HashMap<>();

    private final JavaPlugin plugin;
    private final Logger     logger;

    // ─── Inner request record ─────────────────────────────────────────────────

    private static class TpaRequest {
        /** The player who issued /tpa or /tphere */
        final UUID    requesterUUID;
        final String  requesterName;
        /** True = /tphere (target teleports to requester); False = /tpa (requester teleports to target) */
        final boolean isTphere;
        /** The Bukkit scheduler task ID for the expiry timer */
        int taskId = -1;

        TpaRequest(UUID requesterUUID, String requesterName, boolean isTphere) {
            this.requesterUUID = requesterUUID;
            this.requesterName = requesterName;
            this.isTphere      = isTphere;
        }
    }

    // ─── Constructor ─────────────────────────────────────────────────────────

    public TpaSystem(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[TpaSystem] /tpa, /tphere, /tpaccept, /tpdeny loaded.");
    }

    // ─── CommandExecutor ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {

            case "tpa":
                return handleTpa(player, args, false);

            case "tphere":
                return handleTpa(player, args, true);

            case "tpaccept":
                return handleTpAccept(player);

            case "tpdeny":
                return handleTpDeny(player);

            default:
                return false;
        }
    }

    // ─── /tpa & /tphere ──────────────────────────────────────────────────────

    /**
     * @param isTphere true = /tphere (target teleports to sender), false = /tpa (sender teleports to target)
     */
    private boolean handleTpa(Player requester, String[] args, boolean isTphere) {
        if (args.length < 1) {
            requester.sendMessage(ChatColor.RED + "Usage: /" + (isTphere ? "tphere" : "tpa") + " <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            requester.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
            return true;
        }

        if (target.equals(requester)) {
            requester.sendMessage(ChatColor.RED + "You cannot send a teleport request to yourself.");
            return true;
        }

        // Cancel any existing request the same target already has pending from this requester
        // (If the target already has a different pending request, we overwrite it and notify)
        TpaRequest existing = pendingRequests.get(target.getUniqueId());
        if (existing != null) {
            // Cancel old expiry task
            cancelTask(existing.taskId);
            if (existing.requesterUUID.equals(requester.getUniqueId())) {
                requester.sendMessage(ChatColor.YELLOW + "⟳ Your previous request to " + target.getName() + " was replaced.");
            } else {
                // Notify old requester their request was superseded
                Player oldRequester = Bukkit.getPlayer(existing.requesterUUID);
                if (oldRequester != null) {
                    oldRequester.sendMessage(ChatColor.GRAY + "Your teleport request to " + target.getName() + " was superseded by a new request.");
                }
                requester.sendMessage(ChatColor.YELLOW + "⟳ " + target.getName() + " had a pending request — it has been replaced with yours.");
            }
        }

        // Create and register new request
        TpaRequest request = new TpaRequest(requester.getUniqueId(), requester.getName(), isTphere);
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(target), REQUEST_TIMEOUT_SECS * 20L).getTaskId();
        request.taskId = taskId;
        pendingRequests.put(target.getUniqueId(), request);

        // Notify requester
        if (isTphere) {
            requester.sendMessage(
                ChatColor.GOLD + "📨 Sent a teleport request to " + ChatColor.YELLOW + target.getName() +
                ChatColor.GOLD + " — asking them to come to you."
            );
        } else {
            requester.sendMessage(
                ChatColor.GOLD + "📨 Sent a teleport request to " + ChatColor.YELLOW + target.getName() +
                ChatColor.GOLD + " — asking to teleport to them."
            );
        }

        // Notify target
        if (isTphere) {
            target.sendMessage(
                ChatColor.AQUA + "📬 " + ChatColor.YELLOW + requester.getName() +
                ChatColor.AQUA + " wants you to teleport to them." +
                "\n" + ChatColor.GREEN + "  /tpaccept" + ChatColor.GRAY + " — accept" +
                "\n" + ChatColor.RED   + "  /tpdeny"   + ChatColor.GRAY + " — deny" +
                "\n" + ChatColor.GRAY  + "(Expires in " + REQUEST_TIMEOUT_SECS + " seconds)"
            );
        } else {
            target.sendMessage(
                ChatColor.AQUA + "📬 " + ChatColor.YELLOW + requester.getName() +
                ChatColor.AQUA + " wants to teleport to you." +
                "\n" + ChatColor.GREEN + "  /tpaccept" + ChatColor.GRAY + " — accept" +
                "\n" + ChatColor.RED   + "  /tpdeny"   + ChatColor.GRAY + " — deny" +
                "\n" + ChatColor.GRAY  + "(Expires in " + REQUEST_TIMEOUT_SECS + " seconds)"
            );
        }
        return true;
    }

    // ─── /tpaccept ────────────────────────────────────────────────────────────

    private boolean handleTpAccept(Player target) {
        TpaRequest request = pendingRequests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(ChatColor.RED + "You have no pending teleport request.");
            return true;
        }
        cancelTask(request.taskId);

        Player requester = Bukkit.getPlayer(request.requesterUUID);
        if (requester == null || !requester.isOnline()) {
            target.sendMessage(ChatColor.RED + "The requester is no longer online.");
            return true;
        }

        if (request.isTphere) {
            // /tphere — target teleports to requester
            target.teleport(requester.getLocation());
            target.sendMessage(ChatColor.GREEN + "✔ Teleporting to " + requester.getName() + "!");
            requester.sendMessage(ChatColor.GREEN + "✔ " + target.getName() + " accepted and is teleporting to you!");
        } else {
            // /tpa — requester teleports to target
            requester.teleport(target.getLocation());
            requester.sendMessage(ChatColor.GREEN + "✔ " + target.getName() + " accepted! Teleporting to them now.");
            target.sendMessage(ChatColor.GREEN + "✔ You accepted " + requester.getName() + "'s teleport request.");
        }
        return true;
    }

    // ─── /tpdeny ─────────────────────────────────────────────────────────────

    private boolean handleTpDeny(Player target) {
        TpaRequest request = pendingRequests.remove(target.getUniqueId());
        if (request == null) {
            target.sendMessage(ChatColor.RED + "You have no pending teleport request.");
            return true;
        }
        cancelTask(request.taskId);

        target.sendMessage(ChatColor.YELLOW + "✖ Teleport request denied.");

        Player requester = Bukkit.getPlayer(request.requesterUUID);
        if (requester != null && requester.isOnline()) {
            requester.sendMessage(ChatColor.RED + "✖ " + target.getName() + " denied your teleport request.");
        }
        return true;
    }

    // ─── Expiry ───────────────────────────────────────────────────────────────

    private void expireRequest(Player target) {
        TpaRequest request = pendingRequests.remove(target.getUniqueId());
        if (request == null) return;

        if (target.isOnline()) {
            target.sendMessage(ChatColor.GRAY + "⏰ The teleport request from " + request.requesterName + " has expired.");
        }
        Player requester = Bukkit.getPlayer(request.requesterUUID);
        if (requester != null && requester.isOnline()) {
            requester.sendMessage(ChatColor.GRAY + "⏰ Your teleport request to " + target.getName() + " has expired (no response).");
        }
    }

    // ─── Player quit cleanup ──────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();

        // Remove any pending request directed AT this player
        TpaRequest request = pendingRequests.remove(quitter.getUniqueId());
        if (request != null) {
            cancelTask(request.taskId);
            Player requester = Bukkit.getPlayer(request.requesterUUID);
            if (requester != null) {
                requester.sendMessage(ChatColor.GRAY + quitter.getName() + " disconnected — teleport request cancelled.");
            }
        }

        // Also scan for any request this player SENT that is pending on another player
        pendingRequests.entrySet().removeIf(entry -> {
            if (entry.getValue().requesterUUID.equals(quitter.getUniqueId())) {
                cancelTask(entry.getValue().taskId);
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target != null) {
                    target.sendMessage(ChatColor.GRAY + quitter.getName() + " disconnected — their teleport request was cancelled.");
                }
                return true;
            }
            return false;
        });
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private void cancelTask(int taskId) {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }
}
