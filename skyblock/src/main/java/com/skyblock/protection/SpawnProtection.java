package com.skyblock.protection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.logging.Logger;

/**
 * Spawn Protection System
 *
 * Translated from spawn_protection.js.
 *
 * Protects a square zone of PROTECTION_RADIUS blocks around SPAWN_CENTER.
 * Players with the bypass tag ("sp_bypass") are exempt.
 *
 * Mine exception: the rectangular region defined by MINE_EXCEPTION is
 * inside the protected radius but allows block BREAKING only (mining).
 * Placing blocks in the mine is still blocked.
 *
 * Constants match JS exactly:
 *   SPAWN_CENTER      = (0, 0)
 *   PROTECTION_RADIUS = 1000
 *   BYPASS_TAG        = "sp_bypass"
 *   Mine: x 255–293, y 150–205, z -19–19
 */
public class SpawnProtection implements Listener {

    // ─── Constants ────────────────────────────────────────────────────────────

    private static final double SPAWN_CENTER_X  = 0;
    private static final double SPAWN_CENTER_Z  = 0;
    private static final double PROTECTION_RADIUS = 1000;

    /** Tag that grants full bypass. Exported as constant for admin system. */
    public static final String BYPASS_TAG = "sp_bypass";

    // Mine exception zone
    private static final double MINE_MIN_X = 255, MINE_MAX_X = 293;
    private static final double MINE_MIN_Y = 150, MINE_MAX_Y = 205;
    private static final double MINE_MIN_Z = -19, MINE_MAX_Z =  19;

    private final Logger logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SpawnProtection(Logger logger) {
        this.logger = logger;
        logger.info("[Spawn Protection] Loaded! Protecting " + (int) PROTECTION_RADIUS + " blocks around spawn.");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isInMineException(Location loc) {
        return loc.getX() >= MINE_MIN_X && loc.getX() <= MINE_MAX_X
                && loc.getY() >= MINE_MIN_Y && loc.getY() <= MINE_MAX_Y
                && loc.getZ() >= MINE_MIN_Z && loc.getZ() <= MINE_MAX_Z;
    }

    private static boolean isInProtectedZone(Location loc) {
        return Math.abs(loc.getX() - SPAWN_CENTER_X) <= PROTECTION_RADIUS
                && Math.abs(loc.getZ() - SPAWN_CENTER_Z) <= PROTECTION_RADIUS;
    }

    // ─── Events ───────────────────────────────────────────────────────────────

    /**
     * Mirrors world.beforeEvents.playerBreakBlock in spawn_protection.js.
     * Mine exception applies here — players can mine in the mine zone.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getScoreboardTags().contains(BYPASS_TAG)) return;
        if (!isInProtectedZone(event.getBlock().getLocation())) return;
        // Mine exception: allow breaking inside the mine zone
        if (isInMineException(event.getBlock().getLocation())) return;
        event.setCancelled(true);
        player.sendMessage("§cYou cannot break blocks here.");
    }

    /**
     * Mirrors world.afterEvents.playerPlaceBlock in spawn_protection.js.
     * Mine exception does NOT apply here — placing is always blocked in the mine.
     * JS removed the placed block; here we cancel before placement.
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getScoreboardTags().contains(BYPASS_TAG)) return;
        if (!isInProtectedZone(event.getBlock().getLocation())) return;
        event.setCancelled(true);
        player.sendMessage("§cYou cannot place blocks here.");
    }
}
