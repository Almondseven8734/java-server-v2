package com.skyblock.spawn;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

/**
 * Spawn / Hub Command & Join Teleport
 *
 * Translated from spawn_command.js.
 *
 * Features:
 *   - On initial join, teleports the player to spawn after a 5-tick delay.
 *   - /spawn and /hub — teleport to SPAWN_LOCATION.
 *
 * Constants match JS exactly:
 *   SPAWN_LOCATION = (0.5, 286, 0.5)
 *   SPAWN_YAW = 180  (facing south), SPAWN_PITCH = 0
 *
 * Register commands in plugin.yml:
 *   commands:
 *     spawn:
 *       description: Teleport to spawn
 *     hub:
 *       description: Teleport to hub/spawn
 */
public class SpawnCommand implements CommandExecutor, Listener {

    // ─── Constants ────────────────────────────────────────────────────────────

    private static final double SPAWN_X     =   0.5;
    private static final double SPAWN_Y     = 284.0;
    private static final double SPAWN_Z     =   0.5;
    /** yaw=180 → facing south (matches JS SPAWN_ROTATION.y = 180) */
    private static final float  SPAWN_YAW   = 180.0f;
    private static final float  SPAWN_PITCH =   0.0f;

    private static final long JOIN_DELAY_TICKS = 5L;

    private final JavaPlugin plugin;
    private final Logger     logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SpawnCommand(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[Spawn] Spawn command loaded — location: "
                + SPAWN_X + ", " + SPAWN_Y + ", " + SPAWN_Z + " facing south.");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Location spawnLocation() {
        World world = plugin.getServer().getWorld("world");
        if (world == null) return null;
        return new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
    }

    private void teleportToSpawn(Player player, String message) {
        Location dest = spawnLocation();
        if (dest == null) {
            player.sendMessage("§cCould not find world.");
            return;
        }
        player.teleport(dest);
        // Only remove flight for survival/adventure players — creative players keep their flight
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        player.sendMessage(message);
    }

    // ─── PlayerJoinEvent ──────────────────────────────────────────────────────

    /**
     * Mirrors world.afterEvents.playerSpawn.subscribe (initialSpawn check) in JS.
     * In Bukkit there is no "initialSpawn" flag; use PlayerJoinEvent which only
     * fires once — on first connection (not on respawn).
     *
     * A 5-tick delay ensures the player is fully loaded before teleporting,
     * matching the JS: system.runTimeout(..., 5).
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Teleport every player to hub/spawn on login, regardless of prior location
        new BukkitRunnable() {
            @Override public void run() {
                try {
                    String msg = player.hasPlayedBefore()
                            ? "§aTeleported to hub!"
                            : "§e§l[WELCOME]\n§aYou have been teleported to spawn!";
                    teleportToSpawn(player, msg);
                    logger.info("[SPAWN] " + player.getName()
                            + " joined and was teleported to hub (0.5, 284, 0.5)");
                } catch (Exception e) {
                    logger.severe("[SPAWN] Error teleporting " + player.getName()
                            + " on join: " + e.getMessage());
                }
            }
        }.runTaskLater(plugin, JOIN_DELAY_TICKS);
    }

    // ─── CommandExecutor ──────────────────────────────────────────────────────

    /**
     * Handles both /spawn and /hub.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is player-only.");
            return true;
        }

        String msg = label.equalsIgnoreCase("hub")
                ? "§aTeleported to hub!"
                : "§aTeleported to spawn!";

        try {
            teleportToSpawn(player, msg);
        } catch (Exception e) {
            player.sendMessage("§cFailed to teleport: " + e.getMessage());
        }
        return true;
    }
}
