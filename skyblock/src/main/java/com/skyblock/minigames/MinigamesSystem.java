package com.skyblock.minigames;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minigames System
 *
 * Translated from minigames_command.js + minigames_zone.js.
 *
 * /minigames — sends the player to the minigames server via Bungeecord/Velocity.
 *
 * Zone listener: when a player walks into the transfer zone defined below,
 * they are automatically transferred (with a 5-second cooldown to prevent
 * repeated triggers).
 *
 * Bedrock used @minecraft/server-admin transferPlayer(). On Java/Spigot the
 * equivalent is a BungeeCord plugin-messaging channel message or a Velocity
 * equivalent. This class sends the standard "BungeeCord" → "Connect" message.
 * Make sure BungeeCord channel is registered in your plugin's onEnable().
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     minigames:
 *       description: Transfer to the minigames server
 *       usage: /minigames
 *
 *   softdepend: [BungeeCord]
 */
public class MinigamesSystem implements CommandExecutor, Listener {

    // ─── Server constants ─────────────────────────────────────────────────────
    /** Bungeecord server name for the minigames server. */
    private static final String MINIGAMES_SERVER_NAME = "minigames";

    // ─── Transfer zone (mirrors TRANSFER_ZONE in minigames_zone.js) ──────────
    private static final double ZONE_MIN_X = -1,  ZONE_MAX_X = 1;
    private static final double ZONE_MIN_Y = 284,  ZONE_MAX_Y = 287;
    private static final double ZONE_MIN_Z = 18,   ZONE_MAX_Z = 19;

    /** 5-second cooldown in milliseconds (matches 100-tick Bedrock cooldown). */
    private static final long COOLDOWN_MS = 5_000L;

    // ─── State ────────────────────────────────────────────────────────────────
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final JavaPlugin plugin;
    private final Logger logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public MinigamesSystem(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[Minigames] /minigames command and zone transfer loaded.");
    }

    // ─── /minigames command ───────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        transferToMinigames((Player) sender);
        return true;
    }

    // ─── Zone detection (PlayerMoveEvent) ─────────────────────────────────────

    /**
     * Fires when a player moves. Mirrors the 10-tick runInterval check in
     * minigames_zone.js. PlayerMoveEvent is more efficient than polling.
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only evaluate when the player crosses a block boundary
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (isOnCooldown(player)) return;
        if (!isInTransferZone(event.getTo())) return;

        logger.info("[Minigames Zone] Player " + player.getName() + " entered transfer zone.");
        setCooldown(player);
        transferToMinigames(player);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void transferToMinigames(Player player) {
        player.sendMessage("§e§l[MINIGAMES]");
        player.sendMessage("§aTransferring you to the minigames server...");
        player.sendMessage("§7Connecting to " + MINIGAMES_SERVER_NAME);

        try {
            // BungeeCord plugin-messaging connect
            com.google.common.io.ByteArrayDataOutput out =
                    com.google.common.io.ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(MINIGAMES_SERVER_NAME);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
            logger.info("[Minigames] Transferring " + player.getName()
                    + " to server '" + MINIGAMES_SERVER_NAME + "'.");
        } catch (Exception e) {
            player.sendMessage("§cFailed to transfer to minigames server: " + e.getMessage());
            logger.warning("[Minigames] Transfer error for " + player.getName() + ": " + e.getMessage());
        }
    }

    private static boolean isInTransferZone(Location loc) {
        return loc.getX() >= ZONE_MIN_X && loc.getX() <= ZONE_MAX_X
                && loc.getY() >= ZONE_MIN_Y && loc.getY() <= ZONE_MAX_Y
                && loc.getZ() >= ZONE_MIN_Z && loc.getZ() <= ZONE_MAX_Z;
    }

    private boolean isOnCooldown(Player player) {
        Long last = cooldowns.get(player.getUniqueId());
        if (last == null) return false;
        if (System.currentTimeMillis() - last >= COOLDOWN_MS) {
            cooldowns.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }
}
