package com.skyblock.island;

import com.skyblock.island.IslandData;
import com.skyblock.island.IslandStorage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Island Portal Zone
 *
 * Translated from island_portal.js.
 *
 * Checks every 10 ticks (0.5 s) whether any player is inside the portal zone.
 * If so, teleports them to their island home with a 100-tick (5 s) cooldown
 * per player.
 *
 * Portal zone constants mirror the JS:
 *   minX=-1, maxX=1, minY=284, maxY=287, minZ=-20, maxZ=-16
 */
public class IslandPortal implements Listener {

    // ─── Portal zone constants ─────────────────────────────────────────────────
    private static final double MIN_X =  -1;
    private static final double MAX_X =   1;
    private static final double MIN_Y = 284;
    private static final double MAX_Y = 287;
    private static final double MIN_Z = -20;
    private static final double MAX_Z = -16;

    /** 100 ticks = 5 seconds (same as JS COOLDOWN_TICKS = 100) */
    private static final long COOLDOWN_TICKS = 100L;

    /** Check interval: 10 ticks = 0.5 s (same as JS system.runInterval(..., 10)) */
    private static final long CHECK_INTERVAL = 10L;

    /** Player home Y level (matches island_portal.js teleport Y = 75) */
    private static final double HOME_Y = 75;

    // ─── State ────────────────────────────────────────────────────────────────

    /** Map<playerId, cooldown-expiry server tick> */
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private final IslandStorage storage;
    private final JavaPlugin     plugin;
    private final Logger         logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public IslandPortal(IslandStorage storage, JavaPlugin plugin, Logger logger) {
        this.storage = storage;
        this.plugin  = plugin;
        this.logger  = logger;
        startTask();
        logger.info("[Skyblock] Island portal zone loaded!");
    }

    // ─── Task ─────────────────────────────────────────────────────────────────

    private void startTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = plugin.getServer().getCurrentTick();
                World overworld = plugin.getServer().getWorld("world"); // adjust name as needed

                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    try {
                        // Skip if on cooldown
                        Long expiry = cooldowns.get(player.getUniqueId());
                        if (expiry != null) {
                            if (now < expiry) continue;
                            cooldowns.remove(player.getUniqueId());
                        }

                        if (!isInPortalZone(player)) continue;

                        // Set cooldown
                        cooldowns.put(player.getUniqueId(), now + COOLDOWN_TICKS);

                        IslandData island = storage.getIslandByPlayerId(
                                player.getUniqueId().toString());
                        if (island == null) {
                            player.sendMessage("§cYou don't have an island! Use §e/is create §cto make one.");
                            continue;
                        }

                        if (overworld == null) {
                            logger.warning("[Skyblock Portal] Could not find overworld!");
                            continue;
                        }

                        Location dest = new Location(overworld,
                                island.homeX, HOME_Y, island.homeZ);
                        player.teleport(dest);
                        player.sendMessage("§aTeleported to your island!");

                    } catch (Exception e) {
                        logger.severe("[Skyblock Portal] Error for " + player.getName() + ": " + e.getMessage());
                    }
                }
            }
        }.runTaskTimer(plugin, CHECK_INTERVAL, CHECK_INTERVAL);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isInPortalZone(Player player) {
        Location loc = player.getLocation();
        return loc.getX() >= MIN_X && loc.getX() <= MAX_X
                && loc.getY() >= MIN_Y && loc.getY() <= MAX_Y
                && loc.getZ() >= MIN_Z && loc.getZ() <= MAX_Z;
    }
}
